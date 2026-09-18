package com.example.loanlimit.loanlimitbatchrun.service

import com.example.loanlimit.bankcallresult.repository.BankCallResultRepository
import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.loanlimitbatchrun.entity.LoanLimitBatchRun
import com.example.loanlimit.loanlimitbatchrun.entity.RunStatus
import com.example.loanlimit.loanlimitbatchrun.repository.LoanLimitBatchRunRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Optional

/**
 * status='FAILED' 에는 두 가지가 섞인다 — 은행을 다 호출했는데 성공이 0인
 * 경우(부하 신호)와 fan-out 자체가 예외로 중단된 경우(코드·설정 문제).
 * 측정 표는 DB만 보고 만들므로 이 둘이 구분되지 않으면 버그가 천장으로 읽힌다.
 *
 * fail_reason 이 그 구분자다. 예외로 중단된 쪽만 채워진다.
 */
class FailReasonRecordedTest {

    private val batchRunRepository = mock<LoanLimitBatchRunRepository>()
    private val callResultRepository = mock<BankCallResultRepository>()
    private val service = LoanLimitBatchRunService(
        mock<AppProperties>(),
        batchRunRepository,
        callResultRepository,
    )

    private fun givenRun(saved: MutableList<LoanLimitBatchRun>): LoanLimitBatchRun {
        val runEntity = LoanLimitBatchRun(id = RUN_ID, requestedBankCount = 50)
        whenever(batchRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runEntity))
        whenever(batchRunRepository.save(any<LoanLimitBatchRun>())).doAnswer {
            val entity = it.arguments[0] as LoanLimitBatchRun
            saved.add(entity)
            entity
        }
        return runEntity
    }

    @Test
    fun `예외로 중단되면 사유가 저장된다`() {
        val saved = mutableListOf<LoanLimitBatchRun>()
        givenRun(saved)
        whenever(callResultRepository.countByRunId(RUN_ID)).thenReturn(12L)
        whenever(callResultRepository.countByRunIdAndSuccess(RUN_ID, true)).thenReturn(3L)

        service.markRunFailed(RUN_ID, "Connection pool exhausted")

        assertEquals(1, saved.size)
        assertEquals(RunStatus.FAILED, saved[0].status)
        assertEquals("Connection pool exhausted", saved[0].failReason)
    }

    @Test
    fun `사유가 없어도 빈 칸으로 두지 않는다`() {
        val saved = mutableListOf<LoanLimitBatchRun>()
        givenRun(saved)
        whenever(callResultRepository.countByRunId(RUN_ID)).thenReturn(0L)
        whenever(callResultRepository.countByRunIdAndSuccess(RUN_ID, true)).thenReturn(0L)

        service.markRunFailed(RUN_ID, null)

        // null 로 두면 "은행 다 호출하고 성공 0" 쪽과 구분이 안 된다.
        assertEquals("unknown", saved[0].failReason)
    }

    @Test
    fun `긴 예외 메시지는 잘라서 저장한다`() {
        val saved = mutableListOf<LoanLimitBatchRun>()
        givenRun(saved)
        whenever(callResultRepository.countByRunId(RUN_ID)).thenReturn(0L)
        whenever(callResultRepository.countByRunIdAndSuccess(RUN_ID, true)).thenReturn(0L)

        // 자르지 않으면 컬럼 길이를 넘겨 저장이 통째로 실패하고,
        // run 이 FAILED 로 표시조차 되지 않는다.
        service.markRunFailed(RUN_ID, "x".repeat(LoanLimitBatchRunService.FAIL_REASON_MAX + 200))

        assertEquals(LoanLimitBatchRunService.FAIL_REASON_MAX, saved[0].failReason?.length)
    }

    @Test
    fun `서로게이트 쌍 가운데를 자르지 않는다`() {
        val saved = mutableListOf<LoanLimitBatchRun>()
        givenRun(saved)
        whenever(callResultRepository.countByRunId(RUN_ID)).thenReturn(0L)
        whenever(callResultRepository.countByRunIdAndSuccess(RUN_ID, true)).thenReturn(0L)

        // 경계 바로 앞이 서로게이트 쌍의 앞쪽이 되도록 만든다. 그냥 자르면
        // 홀로 남은 서로게이트가 되고 MySQL이 그 문자열을 거부한다.
        val reason = "x".repeat(LoanLimitBatchRunService.FAIL_REASON_MAX - 1) + "😀".repeat(10)

        service.markRunFailed(RUN_ID, reason)

        val stored = saved[0].failReason!!
        assertEquals(LoanLimitBatchRunService.FAIL_REASON_MAX - 1, stored.length)
        assertFalse(Character.isHighSurrogate(stored[stored.length - 1]))
    }

    @Test
    fun `은행을 다 호출하고 성공이 0이면 사유가 비어 있다`() {
        val saved = mutableListOf<LoanLimitBatchRun>()
        givenRun(saved)
        whenever(callResultRepository.countByRunId(RUN_ID)).thenReturn(50L)
        whenever(callResultRepository.countByRunIdAndSuccess(RUN_ID, true)).thenReturn(0L)

        service.finalizeRunStatus(RUN_ID)

        assertEquals(RunStatus.FAILED, saved[0].status)
        // 부하 신호 쪽. 이게 null 이 아니게 되면 두 경로 구분이 무너진다.
        assertNull(saved[0].failReason)
    }

    companion object {
        private const val RUN_ID = 7L
    }
}
