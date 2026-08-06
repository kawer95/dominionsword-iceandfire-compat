package com.arxyt.dominionsword.iceandfirecompat.control;

/**
 * Pluggable source of the active {@link DragonControlMode}.
 *
 * <p>This add-on ships with the three built-in modes and defaults to
 * {@link DragonControlMode#OWNER_ONLY}. A future Dominion Sword server configuration can
 * bind a source here without changing any dragon behaviour code.
 */
@FunctionalInterface
public interface DragonControlModeSource {
    DragonControlMode current();
}
