package dev.straza.approver.shared.ui

/**
 * Whether the person is in the middle of pairing: the viewfinder is open, or a
 * scanned or typed code is being processed.
 *
 * The foreground re-read of the enrollment store must leave such a screen
 * alone. A system sheet (the camera permission ask, a call banner) bounces the
 * app through background and foreground, and the re-read would otherwise
 * replace the viewfinder the granted permission just opened. The scan ends in
 * an enroll or a cancel, and both re-read the store. A replace prompt is not in
 * flight: its payload does not survive a foreground, so the prompt is rebuilt.
 */
fun enrollmentInFlight(state: UiState): Boolean =
    (state as? UiState.NeedsEnrollment)?.let { it.scanning || it.busy } == true
