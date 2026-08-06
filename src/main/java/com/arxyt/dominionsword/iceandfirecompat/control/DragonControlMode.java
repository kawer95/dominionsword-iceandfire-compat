package com.arxyt.dominionsword.iceandfirecompat.control;

/**
 * Built-in dragon control permission modes.
 *
 * <p>These modes are evaluated by {@link DragonControlPolicy} and apply uniformly to
 * selection, boarding, takeoff, landing, movement and attacks. The main Dominion Sword
 * server config is expected to bind a {@link DragonControlModeSource} later; until then
 * the default is {@link #OWNER_ONLY}.
 */
public enum DragonControlMode {
    /** Only the tamed owner may control the dragon. */
    OWNER_ONLY,
    /** Any tamed dragon may be controlled, regardless of its owner. */
    TAMED_ONLY,
    /** Every living dragon may be controlled, including wild ones. */
    ALL
}
