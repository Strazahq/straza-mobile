package dev.straza.approver.shared.ui

import dev.straza.approver.shared.flow.ApprovalScreen
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnrollmentInFlightTest {

    @Test
    fun `an open viewfinder is in flight with or without deployments`() {
        assertTrue(enrollmentInFlight(UiState.NeedsEnrollment(scanning = true)))
        assertTrue(enrollmentInFlight(UiState.NeedsEnrollment(scanning = true, hasDeployments = true)))
    }

    @Test
    fun `a scanned or typed code being processed is in flight`() {
        assertTrue(enrollmentInFlight(UiState.NeedsEnrollment(busy = true)))
        assertTrue(enrollmentInFlight(UiState.NeedsEnrollment(busy = true, scanning = true, hasDeployments = true)))
    }

    @Test
    fun `an idle pairing screen is not in flight even with an error or deployments`() {
        assertFalse(enrollmentInFlight(UiState.NeedsEnrollment()))
        assertFalse(enrollmentInFlight(UiState.NeedsEnrollment(error = "Camera access is needed to scan the enrollment code.")))
        assertFalse(enrollmentInFlight(UiState.NeedsEnrollment(hasDeployments = true)))
    }

    @Test
    fun `a replace prompt is not in flight because its payload does not survive a foreground`() {
        val prompt = ReplacePrompt(existingName = "prod", claimedName = "prod")
        assertFalse(enrollmentInFlight(UiState.NeedsEnrollment(confirmReplace = prompt, hasDeployments = true)))
    }

    @Test
    fun `no other screen is in flight`() {
        assertFalse(enrollmentInFlight(UiState.Approvals(screen = ApprovalScreen.Loading)))
    }
}
