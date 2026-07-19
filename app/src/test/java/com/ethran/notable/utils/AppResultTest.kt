package com.ethran.notable.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppResultTest {

    private val sampleError = DomainError.NotFound("widget")

    @Test
    fun map_transforms_success_and_passes_through_error() {
        val ok: AppResult<Int, DomainError> = AppResult.Success(2)
        assertEquals(AppResult.Success(4), ok.map { it * 2 })

        val err: AppResult<Int, DomainError> = AppResult.Error(sampleError)
        assertEquals(AppResult.Error(sampleError), err.map { it * 2 })
    }

    @Test
    fun flatMap_chains_success_and_short_circuits_on_error() {
        val ok: AppResult<Int, DomainError> = AppResult.Success(3)
        val chained = ok.flatMap { AppResult.Success(it + 1) }
        assertEquals(AppResult.Success(4), chained)

        val err: AppResult<Int, DomainError> = AppResult.Error(sampleError)
        val short = err.flatMap<Int, DomainError, Int> { AppResult.Success(99) }
        assertEquals(AppResult.Error(sampleError), short)
    }

    @Test
    fun mapError_transforms_only_error_branch() {
        val mapped = AppResult.Error(sampleError).mapError {
            DomainError.UnexpectedState("wrapped:${it.userMessage}")
        }
        assertTrue(mapped is AppResult.Error)
        assertEquals(
            "wrapped:widget was not found.",
            (mapped as AppResult.Error).error.userMessage
        )
    }

    @Test
    fun fold_dispatches_to_correct_branch() {
        val ok: AppResult<Int, DomainError> = AppResult.Success(7)
        val err: AppResult<Int, DomainError> = AppResult.Error(sampleError)

        assertEquals("ok:7", ok.fold({ "ok:$it" }, { "err:${it.userMessage}" }))
        assertEquals("err:widget was not found.", err.fold({ "ok:$it" }, { "err:${it.userMessage}" }))
    }

    @Test
    fun getOrNull_and_getOrElse_behave_as_expected() {
        val ok: AppResult<Int, DomainError> = AppResult.Success(10)
        val err: AppResult<Int, DomainError> = AppResult.Error(sampleError)

        assertEquals(10, ok.getOrNull())
        assertNull(err.getOrNull())

        assertEquals(10, ok.getOrElse { -1 })
        assertEquals(-1, err.getOrElse { -1 })
    }

    @Test
    fun onSuccess_and_onError_invoke_appropriate_side_effects() {
        var seenSuccess: Int? = null
        var seenError: DomainError? = null

        val ok: AppResult<Int, DomainError> = AppResult.Success(5)
        ok.onSuccess { seenSuccess = it }.onError { seenError = it }
        assertEquals(5, seenSuccess)
        assertNull(seenError)

        seenSuccess = null
        val err: AppResult<Int, DomainError> = AppResult.Error(sampleError)
        err.onSuccess { seenSuccess = it }.onError { seenError = it }
        assertNull(seenSuccess)
        assertSame(sampleError, seenError)
    }

    @Test
    fun plus_combines_two_simple_errors_into_multiple() {
        val combined = DomainError.NetworkError("net") + DomainError.DatabaseError("db")
        assertEquals(2, combined.errors.size)
        assertTrue(combined.errors[0] is DomainError.NetworkError)
        assertTrue(combined.errors[1] is DomainError.DatabaseError)
    }

    @Test
    fun plus_flattens_when_either_side_is_multiple() {
        val left = DomainError.NetworkError("a") + DomainError.NetworkError("b")
        val right = DomainError.DatabaseError("c") + DomainError.DatabaseError("d")

        val combined = left + right
        assertEquals(4, combined.errors.size)

        val withSingle = left + DomainError.NotFound("x")
        assertEquals(3, withSingle.errors.size)
    }

    @Test
    fun extendMessage_appends_only_when_extra_is_non_blank() {
        val err = DomainError.NotFound("notebook")
        assertEquals("notebook was not found.", err.extendMessage(""))
        assertEquals("notebook was not found.", err.extendMessage("   "))
        assertEquals("notebook was not found.. retrying", err.extendMessage("retrying"))
    }
}
