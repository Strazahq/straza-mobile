package dev.straza.approver.shared.security

/**
 * What the platform needs to reach its own storage: an Android `Context`,
 * nothing on iOS. Passed explicitly, not read from a global, so the dependency
 * is visible in every signature that needs it.
 */
expect class PlatformContext
