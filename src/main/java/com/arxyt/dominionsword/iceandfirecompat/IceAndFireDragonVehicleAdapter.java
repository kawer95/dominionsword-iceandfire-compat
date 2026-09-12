package com.arxyt.dominionsword.iceandfirecompat;

import com.arxyt.dominionsword.api.DominionVehicleAdapter;
import com.arxyt.dominionsword.api.VehicleDismounts;
import com.arxyt.dominionsword.api.DominionTargeting;
import com.arxyt.dominionsword.control.PlayerControl;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonAutopilot;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonControlPolicy;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonRideState;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;

/**
 * Dominion Sword vehicle bridge for Ice And Fire CE dragons.
 *
 * <p>Dragons stay {@code Mob} entities inside Ice And Fire; Dominion Sword only sees them as
 * self-driving vehicles. The native ground navigator and flight manager perform the actual
 * movement, and {@link DragonAutopilot} feeds goals and phase transitions.
 */
public final class IceAndFireDragonVehicleAdapter implements DominionVehicleAdapter {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String ACTION_TAKEOFF = "dominionsword_iceandfire_compat:takeoff";
    public static final String ACTION_LAND = "dominionsword_iceandfire_compat:land";
    public static final String ACTION_AUTO_CONTROL = "dominionsword_iceandfire_compat:auto_control";

    @Override
    public int priority() {
        return 80;
    }

    @Override
    public boolean supports(Entity vehicle) {
        return vehicle instanceof EntityDragonBase;
    }

    @Override
    public boolean selectable(Entity vehicle) {
        if (!(vehicle instanceof EntityDragonBase dragon)) return false;
        return valid(dragon) && dragon.getControllingPassenger() == null;
    }

    @Override
    public boolean canSelfDrive(Entity vehicle) {
        return vehicle instanceof EntityDragonBase dragon && valid(dragon) && DragonRideState.riderId(dragon) == null;
    }

    @Override
    public AABB selectionBounds(Entity vehicle) {
        if (!(vehicle instanceof EntityDragonBase dragon)) return vehicle.getBoundingBox().inflate(1.0D, 0.5D, 1.0D);
        // Ground-projected footprint scaled by the dragon's actual render size (feeding/growth
        // level, up to ~30 for a maxed elder).  The hitbox alone is capped and too small for
        // large dragons, so the clickable circle must follow the model size.
        double half = Math.max(1.75D, dragon.getRenderSize() * 0.5D);
        double groundY = groundProjectionY(dragon);
        double cx = dragon.getX();
        double cz = dragon.getZ();
        return new AABB(cx - half, groundY + 0.02D, cz - half, cx + half, groundY + 0.2D, cz + half);
    }

    @Override
    public List<Vec3> selectionCorners(Entity vehicle) {
        if (!(vehicle instanceof EntityDragonBase)) return DominionVehicleAdapter.super.selectionCorners(vehicle);
        AABB footprint = selectionBounds(vehicle);
        double y = footprint.maxY + 0.03D;
        return List.of(
                new Vec3(footprint.minX, y, footprint.minZ),
                new Vec3(footprint.maxX, y, footprint.minZ),
                new Vec3(footprint.maxX, y, footprint.maxZ),
                new Vec3(footprint.minX, y, footprint.maxZ));
    }

    @Override
    public boolean groundProjectedSelection(Entity vehicle) {
        return vehicle instanceof EntityDragonBase;
    }

    private static double groundProjectionY(EntityDragonBase dragon) {
        // Scan downward from the dragon's body for the first solid block, like the helicopter
        // adapters: the world heightmap above the dragon is a ceiling, not the ground, so the
        // projection ring must sit on the real floor below the dragon instead of a roof.
        net.minecraft.world.level.Level level = dragon.level();
        int blockX = (int) Math.floor(dragon.getX());
        int blockZ = (int) Math.floor(dragon.getZ());
        int y = Math.min((int) Math.floor(dragon.getY()), level.getMaxBuildHeight() - 1);
        while (y >= level.getMinBuildHeight()) {
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(blockX, y, blockZ);
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(level, pos);
            if (!state.is(net.minecraft.world.level.block.Blocks.BARRIER) && !shape.isEmpty()) {
                return y + shape.max(net.minecraft.core.Direction.Axis.Y);
            }
            y--;
        }
        return level.getMinBuildHeight();
    }

    @Override
    public HealthView health(Entity vehicle) {
        return vehicle instanceof EntityDragonBase dragon ? new HealthView(dragon.getHealth(), dragon.getMaxHealth()) : null;
    }

    @Override
    public List<SeatView> seats(Entity vehicle) {
        if (!(vehicle instanceof EntityDragonBase dragon)) return List.of();
        return List.of(new SeatView(0, "driver", DragonRideState.riderEntity(dragon)));
    }

    @Override
    public boolean hasDriver(Entity vehicle) {
        return vehicle instanceof EntityDragonBase dragon && DragonRideState.riderEntity(dragon) != null;
    }

    @Override
    public boolean select(ServerPlayer player, Entity vehicle) {
        if (!(vehicle instanceof EntityDragonBase dragon)) return false;
        if (!DragonControlPolicy.allows(player, dragon) || !valid(dragon)) return false;
        if (dragon.getControllingPassenger() != null) return false;
        DragonAutopilot.beginControl(dragon);
        return true;
    }

    @Override
    public boolean release(ServerPlayer player, Entity vehicle) {
        if (!(vehicle instanceof EntityDragonBase dragon)) return false;
        if (player == null) {
                // The main mod calls release(null, ...) when an offline vehicle task is complete.
                // This must NOT end control: forceEndControl() cleared the flying flags mid-air
                // and dropped the dragon out of the sky, which looked like an auto-land.  Flight
                // mode persists - the dragon hovers over the destination until the next order or
                // the manual land button.
            return true;
        }
        if (!canOperate(player, dragon)) return false;
        DragonAutopilot.endControl(dragon);
        return true;
    }

    @Override
    public boolean board(ServerPlayer player, Mob unit, Entity vehicle, int seat, boolean force) {
        if (!(vehicle instanceof EntityDragonBase dragon) || seat != 0 || unit == null || player == null) return false;
        if (!canOperate(player, dragon) || unit.level() != dragon.level() || !unit.isAlive() || unit.isRemoved()) return false;
        if (dragon.getControllingPassenger() != null) return false;
        if (dragon.getDragonStage() < 3 || dragon.isBaby()) return false;
        if (dragon.isFlying() || dragon.isHovering()) return false;
        if (unit.getVehicle() == dragon) return true;
        boolean occupied = dragon.getPassengers().stream().anyMatch(passenger -> passenger != unit);
        if (occupied) return false;
        // Register the designated rider BEFORE mounting so the protection mixins can intercept
        // the very first native passenger update.
        DragonRideState.setRiderId(dragon, unit.getUUID());
        if (!unit.startRiding(dragon, true)) {
            DragonRideState.clearRiderMarkers(dragon, unit);
            LOGGER.warn("[DS-Iaf] board failed: {} could not mount dragon {}", unit.getUUID(), dragon.getUUID());
            return false;
        }
        DragonRideState.setRiderDragon(unit, dragon.getUUID());
        DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        DragonAutopilot.wake(dragon);
        dragon.getNavigation().stop();
        return true;
    }

    @Override
    public boolean supportsPlayerBoarding(ServerPlayer player, Entity vehicle, int seat) {
        return player != null && player.isAlive() && vehicle instanceof EntityDragonBase dragon && seat == 0
                && canOperate(player, dragon) && dragon.getDragonStage() >= 3 && !dragon.isBaby()
                && !dragon.isFlying() && !dragon.isHovering();
    }

    @Override
    public boolean boardPlayer(ServerPlayer player, Entity vehicle, int seat, boolean force) {
        if (!supportsPlayerBoarding(player, vehicle, seat) || !(vehicle instanceof EntityDragonBase dragon)) return false;
        if (player.getVehicle() == dragon) return true;
        if (dragon.getControllingPassenger() != null || dragon.getPassengers().stream().anyMatch(passenger -> passenger != player)) return false;
        DragonRideState.setRiderId(dragon, player.getUUID());
        if (!player.startRiding(dragon, true)) {
            DragonRideState.setRiderId(dragon, null);
            return false;
        }
        DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        DragonAutopilot.wake(dragon);
        dragon.getNavigation().stop();
        return true;
    }

    @Override
    public boolean dismount(ServerPlayer player, Entity vehicle, int seat) {
        if (!(vehicle instanceof EntityDragonBase dragon) || seat != 0 || !canOperate(player, dragon)) return false;
        Mob rider = DragonRideState.riderEntity(dragon);
        if (rider == null) return false;
        if (dragon.isFlying() || dragon.isHovering()) {
            DragonAutopilot.beginLanding(dragon, dragon.position());
            return false;
        }
        if (!VehicleDismounts.dismount(dragon, rider)) return false;
        DragonRideState.clearRiderMarkers(dragon, rider);
        DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        return true;
    }

    @Override
    public boolean move(ServerPlayer player, Entity vehicle, Vec3 target) {
        if (!(vehicle instanceof EntityDragonBase dragon) || !valid(dragon) || !validTarget(dragon, target)) return false;
        if (player != null && (!DragonControlPolicy.allows(player, dragon) || !player.getUUID().equals(PlayerControl.controller(dragon))
                || player.level() != dragon.level())) return false;
        if (player == null && !authorizedPersistentTask(dragon)) return false;
        DragonAutopilot.updateTask(dragon, target);
        return true;
    }

    @Override
    public boolean attack(ServerPlayer player, Entity vehicle, LivingEntity target) {
        if (!(vehicle instanceof EntityDragonBase dragon) || !valid(dragon) || target == null || !target.isAlive()) return false;
        if (target.level() != dragon.level()) return false;
        if (player != null && (!DragonControlPolicy.allows(player, dragon) || !player.getUUID().equals(PlayerControl.controller(dragon))
                || player.level() != dragon.level()
                || !DominionTargeting.isHostileCandidate(player, java.util.List.of(dragon), target))) return false;
        if (player == null && !authorizedPersistentTask(dragon)) return false;
        LivingEntity currentTarget = dragon.getTarget();
        UUID commanded = DragonRideState.attackTarget(dragon);
        if (commanded != null && commanded.equals(target.getUUID())
                && currentTarget != null && currentTarget.getUUID().equals(target.getUUID())) {
            return true;
        }
        DragonRideState.setAttackTarget(dragon, target.getUUID());
        DragonRideState.setMission(dragon, DragonRideState.Mission.HOVER_ATTACK);
        dragon.setTarget(target);
        DragonAutopilot.wake(dragon);
        // Auto control (or a driver-less dragon, which is forced into auto control) may fly to
        // engage.  Manual mode with a driver must never auto-take-off: a grounded dragon walks
        // to the target and uses its ground melee; only a dragon already airborne from a manual
        // take-off stays in flight for the attack.
        boolean autoFlight = DragonRideState.autoControl(dragon) || DragonRideState.riderId(dragon) == null
                || dragon.isFlying() || dragon.isHovering();
        DragonRideState.setPhase(dragon, autoFlight ? DragonRideState.Phase.CRUISE : DragonRideState.Phase.GROUND);
        dragon.setFlying(autoFlight);
        dragon.setHovering(false);
        return true;
    }

    @Override
    public List<ActionView> actions(Entity vehicle) {
        if (!(vehicle instanceof EntityDragonBase dragon)) return List.of();
        List<ActionView> out = new ArrayList<>();
        out.add(new ActionView(ACTION_TAKEOFF, "@action.dominionsword_iceandfire_compat.takeoff"));
        out.add(new ActionView(ACTION_LAND, "@action.dominionsword_iceandfire_compat.land"));
        // Without a driver the dragon is forced into auto control, so the toggle reads ON even
        // if the persisted flag is off; it stays disabled in actionEnabled().
        out.add(ActionView.toggle(ACTION_AUTO_CONTROL, "@action.dominionsword_iceandfire_compat.auto_control",
                DragonRideState.autoControl(dragon) || DragonRideState.riderId(dragon) == null));
        return out;
    }

    @Override
    public boolean actionEnabled(Entity vehicle, String actionId) {
        if (!(vehicle instanceof EntityDragonBase dragon)) return false;
        boolean mounted = DragonRideState.riderId(dragon) != null;
        // Without a driver the dragon is forced into auto control, so the manual takeoff and
        // landing buttons are disabled entirely.
        boolean auto = DragonRideState.autoControl(dragon) || !mounted;
        // The native onGround() flag is unreliable for a huge dragon held at its task altitude
        // one to three blocks above the actual surface, which kept the takeoff button greyed out
        // permanently; the GROUND phase itself already guarantees the dragon is within four
        // blocks of the terrain and not airborne.
        if (ACTION_TAKEOFF.equals(actionId)) {
            return mounted && !auto && valid(dragon) && !dragon.isFlying() && !dragon.isHovering()
                    && dragon.getDragonStage() >= 3 && dragon.hasFlightClearance();
        }
        if (ACTION_LAND.equals(actionId)) {
            return mounted && !auto && valid(dragon) && (dragon.isFlying() || dragon.isHovering());
        }
        if (ACTION_AUTO_CONTROL.equals(actionId)) {
            return mounted && valid(dragon);
        }
        return false;
    }

    @Override
    public boolean validateAction(ServerPlayer player, Entity vehicle, String actionId, String value) {
        if (!(vehicle instanceof EntityDragonBase dragon) || !canOperate(player, dragon)) return false;
        return actionEnabled(vehicle, actionId);
    }

    @Override
    public boolean performAction(ServerPlayer player, Entity vehicle, String actionId, String value) {
        if (!(vehicle instanceof EntityDragonBase dragon) || !validateAction(player, vehicle, actionId, value)) return false;
        if (ACTION_TAKEOFF.equals(actionId)) {
            DragonAutopilot.beginTakeoff(dragon);
            return true;
        }
        if (ACTION_LAND.equals(actionId)) {
            DragonAutopilot.beginLanding(dragon, dragon.position());
            return true;
        }
        if (ACTION_AUTO_CONTROL.equals(actionId)) {
            if (DragonRideState.riderId(dragon) == null) return false;
            DragonRideState.setAutoControl(dragon, !DragonRideState.autoControl(dragon));
            return true;
        }
        return false;
    }

    private static boolean valid(EntityDragonBase dragon) {
        return dragon != null && !dragon.isRemoved() && dragon.isAlive() && !dragon.isModelDead();
    }

    /** Adapter-boundary validation. Normal command range remains owned by Dominion Sword. */
    private static boolean validTarget(EntityDragonBase dragon, Vec3 target) {
        if (dragon == null || target == null || !Double.isFinite(target.x)
                || !Double.isFinite(target.y) || !Double.isFinite(target.z)) return false;
        if (target.y < dragon.level().getMinBuildHeight() || target.y >= dragon.level().getMaxBuildHeight()) return false;
        return dragon.level().getWorldBorder().isWithinBounds(BlockPos.containing(target));
    }

    private static boolean canOperate(ServerPlayer player, EntityDragonBase dragon) {
        return player != null && valid(dragon) && player.level() == dragon.level()
                && DragonControlPolicy.allows(player, dragon)
                && player.getUUID().equals(PlayerControl.controller(dragon));
    }

    private static boolean authorizedPersistentTask(EntityDragonBase dragon) {
        UUID controller = PlayerControl.controller(dragon);
        return PlayerControl.hasPersistentVehicleTask(dragon) && controller != null
                && DragonControlPolicy.allows(controller, dragon);
    }
}
