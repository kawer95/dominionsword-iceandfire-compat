package com.arxyt.dominionsword.iceandfirecompat;

import com.arxyt.dominionsword.api.DominionSkillProvider;
import com.arxyt.dominionsword.control.PlayerControl;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonAutopilot;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonControlPolicy;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonFlightRegistry;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonRideState;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** AOE skills for Dominion-controlled dragons: orbit breath and strafe breath. */
public final class IceAndFireDragonSkillProvider implements DominionSkillProvider {
    public static final String ORBIT = "dominionsword_iceandfire_compat:orbit_breath";
    public static final String STRAFE = "dominionsword_iceandfire_compat:strafe_breath";
    private static final String ORBIT_ICON = "minecraft:textures/item/ghast_tear.png";
    private static final String STRAFE_ICON = "minecraft:textures/item/spectral_arrow.png";
    public static final double ORBIT_RADIUS = 24.0D;
    public static final double ORBIT_HALF_HEIGHT = 16.0D;
    public static final double ORBIT_RANGE = 96.0D;
    public static final double STRAFE_RADIUS = 16.0D;
    public static final double STRAFE_HALF_HEIGHT = 16.0D;
    public static final double STRAFE_RANGE = 96.0D;
    public static final int STRAFE_COOLDOWN_TICKS = 900;

    @Override
    public int priority() {
        return 90;
    }

    @Override
    public boolean supports(Entity actor) {
        return actor instanceof EntityDragonBase;
    }

    @Override
    public List<SkillView> skills(ServerPlayer commander, Entity actor) {
        if (!(actor instanceof EntityDragonBase dragon) || !DragonRideState.isControlled(dragon)
                || commander == null || commander.level() != dragon.level()
                || !DragonControlPolicy.allows(commander, dragon)
                || !commander.getUUID().equals(PlayerControl.controller(dragon))) {
            return List.of();
        }
        long now = dragon.level().getGameTime();
        int remaining = (int) Math.max(0L,
                Math.min(STRAFE_COOLDOWN_TICKS, DragonRideState.strafeReadyTick(dragon) - now));
        return List.of(
                new SkillView(ORBIT, "@skill.dominionsword_iceandfire_compat.orbit_breath", ORBIT_ICON,
                        SkillType.AOE, true, 0, 0, 0.0D, ORBIT_RANGE,
                        new AoeSpec(ORBIT_RADIUS, ORBIT_HALF_HEIGHT)),
                new SkillView(STRAFE, "@skill.dominionsword_iceandfire_compat.strafe_breath", STRAFE_ICON,
                        SkillType.AOE, true, STRAFE_COOLDOWN_TICKS, remaining, 0.0D, STRAFE_RANGE,
                        new AoeSpec(STRAFE_RADIUS, STRAFE_HALF_HEIGHT)));
    }

    @Override
    public boolean activate(SkillContext context, String skillId) {
        if (!(context.actor() instanceof EntityDragonBase dragon) || context.target() == null
                || context.target().position() == null || !DragonRideState.isControlled(dragon)
                || context.commander() == null || !DragonControlPolicy.allows(context.commander(), dragon)
                || !context.commander().getUUID().equals(PlayerControl.controller(dragon))
                || dragon.level() != context.commander().level()) {
            return false;
        }
        Vec3 center = context.target().position();
        if (!Double.isFinite(center.x) || !Double.isFinite(center.y) || !Double.isFinite(center.z)) return false;
        ServerPlayer player = context.commander();
        if (ORBIT.equals(skillId)) {
            if (!PlayerControl.redirectVehicleMove(player, dragon, center)) return false;
            DragonRideState.setMission(dragon, DragonRideState.Mission.ORBIT);
            DragonRideState.setTask(dragon, center);
            DragonRideState.setAoeSpec(dragon, ORBIT_RADIUS, ORBIT_HALF_HEIGHT);
            wakeForMission(dragon);
            return true;
        }
        if (STRAFE.equals(skillId)) {
            long now = dragon.level().getGameTime();
            if (now < DragonRideState.strafeReadyTick(dragon)) return false;
            if (!PlayerControl.redirectVehicleMove(player, dragon, center)) return false;
            DragonRideState.setMission(dragon, DragonRideState.Mission.STRAFE_APPROACH);
            DragonRideState.setTask(dragon, center);
            DragonRideState.setAoeSpec(dragon, STRAFE_RADIUS, STRAFE_HALF_HEIGHT);
            DragonRideState.setStrafeReadyTick(dragon, now + STRAFE_COOLDOWN_TICKS);
            DragonFlightRegistry.RuntimeState state = DragonFlightRegistry.state(dragon);
            state.strafeStage = 0;
            state.strafeAxis = null;
            wakeForMission(dragon);
            return true;
        }
        return false;
    }

    private static void wakeForMission(EntityDragonBase dragon) {
        DragonAutopilot.wake(dragon);
        // Auto control (or a driver-less dragon, forced into auto control, or a dragon already
        // airborne from a manual take-off) engages flight.  Manual mode with a driver and a
        // grounded dragon must never auto-take-off: the skill stays queued until the player
        // manually takes off.
        boolean autoFlight = DragonRideState.autoControl(dragon) || DragonRideState.riderId(dragon) == null
                || dragon.isFlying() || dragon.isHovering();
        DragonRideState.setPhase(dragon, autoFlight ? DragonRideState.Phase.CRUISE : DragonRideState.Phase.GROUND);
        dragon.setFlying(autoFlight);
        dragon.setHovering(false);
    }
}
