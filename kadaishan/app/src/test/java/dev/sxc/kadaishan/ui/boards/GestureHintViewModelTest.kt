package dev.sxc.kadaishan.ui.boards

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GestureHintViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun opening() = GestureHintViewModel().apply { setForeground(true) }

    @Test fun `loading and empty boards leave the allowance for the first usable board`() = runTest {
        val vm = opening()
        vm.showIfEligible("PHOTO", false)
        assertNull(vm.visibleOwner.value)
        vm.showIfEligible("VIDEO", true)
        assertEquals("VIDEO", vm.visibleOwner.value)
        vm.dismiss()
    }

    @Test fun `timeout hides at three seconds and new rounds cannot show again`() = runTest {
        val vm = opening()
        vm.showIfEligible("PHOTO", true)
        runCurrent()
        advanceTimeBy(2_999)
        assertEquals("PHOTO", vm.visibleOwner.value)
        advanceTimeBy(1)
        runCurrent()
        assertNull(vm.visibleOwner.value)
        vm.showIfEligible("PHOTO", true)
        vm.showIfEligible("VIDEO", true)
        assertNull(vm.visibleOwner.value)
    }

    @Test fun `effective action and settings navigation do not reset the allowance`() = runTest {
        val vm = opening()
        vm.showIfEligible("PHOTO", true)
        vm.dismiss("PHOTO")
        vm.showIfEligible("PHOTO", false)
        vm.showIfEligible("PHOTO", true)
        vm.showIfEligible("VIDEO", true)
        assertNull(vm.visibleOwner.value)
    }

    @Test fun `background loading does not consume allowance and background return never renews it`() = runTest {
        val vm = GestureHintViewModel()
        vm.showIfEligible("PHOTO", true)
        assertNull(vm.visibleOwner.value)
        vm.setForeground(true)
        vm.showIfEligible("VIDEO", true)
        assertEquals("VIDEO", vm.visibleOwner.value)
        vm.setForeground(false)
        assertNull(vm.visibleOwner.value)
        vm.setForeground(true)
        vm.showIfEligible("VIDEO", true)
        assertNull(vm.visibleOwner.value)
    }

    @Test fun `configuration reuse retains consumed state but new Activity opening resets it`() = runTest {
        val retained = opening()
        retained.showIfEligible("PHOTO", true)
        retained.dismiss("PHOTO")
        retained.showIfEligible("PHOTO", true)
        assertNull(retained.visibleOwner.value)
        val reopened = opening()
        reopened.showIfEligible("VIDEO", true)
        assertEquals("VIDEO", reopened.visibleOwner.value)
        reopened.dismiss()
    }

    @Test fun `stale outgoing board cannot dismiss the visible owner`() = runTest {
        val vm = opening()
        vm.showIfEligible("VIDEO", true)
        vm.dismiss("PHOTO")
        assertEquals("VIDEO", vm.visibleOwner.value)
        vm.showIfEligible("PHOTO", false)
        assertEquals("VIDEO", vm.visibleOwner.value)
        vm.dismiss()
    }
}
