package com.arxyt.dominionsword.iceandfirecompat;

import com.arxyt.dominionsword.api.DominionVehicleAdapter;
import com.arxyt.dominionsword.api.VehicleDismounts;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonAutopilot;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonControlPolicy;
import com.arxyt.dominionsword.iceandfirecompat.control.DragonRideState;
import com.iafenvoy.iceandfire.entity.EntityDragonBase;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dominion Sword vehicle bridge for Ice And Fire CE dragons.
 *
 * <p>Dragons stay {@code Mob} entities inside Ice And Fire; Dominion Sword only sees them as
 * self-driving vehicles. The native ground navigator and flight manager perform the actual
 * movement, and {@link DragonAutopilot} feeds goals and phase transitions.
 */
public final class IceAndFireDragonVehicleAdapter implements DominionVehicleAdapter {
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
        return vehicle.getBoundingBox().inflate(1.0D, 0.5D, 1.0D);
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
        if (vehicle instanceof EntityDragonBase dragon) DragonAutopilot.endControl(dragon);
        return true;
    }

    @Override
    public boolean board(ServerPlayer player, Mob unit, Entity vehicle, int seat, boolean force) {
        if (!(vehicle instanceof EntityDragonBase dragon) || seat != 0 || unit == null || player == null) return false;
        if (!DragonControlPolicy.allows(player, dragon) || !valid(dragon)) return false;
        if (dragon.getControllingPassenger() != null) return false;
        if (dragon.getDragonStage() < 3 || dragon.isBaby()) return false;
        if (dragon.isFlying() || dragon.isHovering()) return false;
        if (unit.getVehicle() == dragon) return true;
        boolean occupied = dragon.getPassengers().stream().anyMatch(passenger -> passenger != unit);
        if (occupied) return false;
        if (!unit.startRiding(dragon, true)) return false;
        DragonRideState.setRiderId(dragon, unit.getUUID());
        DragonRideState.setRiderDragon(unit, dragon.getUUID());
        DragonRideState.setPhase(dragon, DragonRideState.Phase.GROUND);
        DragonAutopilot.wake(dragon);
        dragon.getNavigation().stop();
        return true;
    }

    @Override
    public boolean dismount(ServerPlayer player, Entity vehicle, int seat) {
        if (!(vehicle instanceof EntityDragonBase dragon) || seat != 0) return false;
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
        if (!(vehicle instanceof EntityDragonBase dragon) || !valid(dragon) || target == null) return false;
        if (player != null && !DragonControlPolicy.allows(player, dragon)) return false;
        DragonAutopilot.updateTask(dragon, target);
        if (dragon.onGround() && !dragon.isFlying() && DragonRideState.hasTask(dragon) && dragon.hasFlightClearance()) {
            DragonAutopilot.beginTakeoff(dragon);
        }
        return true;
    }

    @Override
    public boolean attack(ServerPlayer player, Entity vehicle, LivingEntity target) {
        if (!(vehicle instanceof EntityDragonBase dragon) || !valid(dragon) || target == null || !target.isAlive()) return false;
        if (player != null && !DragonControlPolicy.allows(player, dragon)) return false;
        LivingEntity currentTarget = dragon.getTarget();
        UUID commanded = DragonRideState.attackTarget(dragon);
        if (commanded != null && commanded.equals(target.getUUID())
                && currentTarget != null && currentTarget.getUUID().equals(target.getUUID())) {
            return true;
        }
        DragonRideState.setAttackTarget(dragon, target.getUUID());
        DragonRideState.setMission(dragon, DragonRideState.Mission.HOVER_ATTACK);
        dragon.setTarget(target);
        if (dragon.onGround() && !dragon.isFlying() && dragon.hasFlightClearance()) {
            DragonAutopilot.beginTakeoff(dragon);
        }
        return true;
    }

    @Override
    public List<ActionView> actions(Entity vehicle) {
        if (!(vehicle instanceof EntityDragonBase dragon)) return List.of();
        List<ActionView> out = new ArrayList<>();
        out.add(new ActionView(ACTION_TAKEOFF, "@action.dominionsword_iceandfire_compat.takeoff"));
        out.add(new ActionView(ACTION_LAND, "@action.dominionsword_iceandfire_compat.land"));
        out.add(ActionView.toggle(ACTION_AUTO_CONTROL, "@action.dominionsword_iceandfire_compat.auto_control", DragonRideState.autoControl(dragon)));
        return out;
    }

    @Override
    public boolean actionEnabled(Entity vehicle, String actionId) {
        if (!(vehicle instanceof EntityDragonBase dragon)) return false;
        boolean mounted = DragonRideState.riderId(dragon) != null;
        boolean auto = DragonRideState.autoControl(dragon);
        if (ACTION_TAKEOFF.equals(actionId)) {
            return mounted && !auto && valid(dragon) && !dragon.isFlying() && !dragon.isHovering()
                    && dragon.onGround() && dragon.getDragonStage() >= 3 && dragon.hasFlightClearance();
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
        if (!(vehicle instanceof EntityDragonBase dragon) || player == null || !DragonControlPolicy.allows(player, dragon)) return false;
        return actionEnabled(vehicle, actionId);
    }

    @Override
    public boolean performAction(ServerPlayer player, Entity vehicle, String actionId, String value) {
        if (!(vehicle instanceof EntityDragonBase dragon)) return false;
        if (ACTION_TAKEOFF.equals(actionId)) {
            DragonAutopilot.beginTakeoff(dragon);
            return true;
        }
        if (ACTION_LAND.equals(actionId)) {
            DragonAutopilot.beginLanding(dragon, dragon.position());
            return true;
        }
        if (ACTION_AUTO_CONTROL.equals(actionId)) {
            DragonRideState.setAutoControl(dragon, !DragonRideState.autoControl(dragon));
            return true;
        }
        return false;
    }

    private static boolean valid(EntityDragonBase dragon) {
        return dragon != null && !dragon.isRemoved() && dragon.isAlive() && !dragon.isModelDead();
    }
}
