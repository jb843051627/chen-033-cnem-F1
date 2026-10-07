package com.fc.v2.service.cnem;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TCnemFeeFlowMapper;
import com.fc.v2.mapper.auto.TCnemInspLedgerMapper;
import com.fc.v2.mapper.auto.TCnemJobMapper;
import com.fc.v2.mapper.auto.TCnemJobOplogMapper;
import com.fc.v2.mapper.auto.TCnemJobRowMapper;
import com.fc.v2.model.auto.TCnemFeeFlow;
import com.fc.v2.model.auto.TCnemInspLedger;
import com.fc.v2.model.auto.TCnemJob;
import com.fc.v2.model.auto.TCnemJobOplog;
import com.fc.v2.model.auto.TCnemJobRow;
import com.fc.v2.model.custom.CnemJobTrace;
import com.fc.v2.service.ITCnemJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检验结论作业全口径集成测试（H2 内存库 + 真实 mapper/事务）。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CnemTestConfig.class)
class TCnemJobLifecycleIT {

    @Autowired
    private ITCnemJobService jobService;
    @Autowired
    private TCnemJobMapper jobMapper;
    @Autowired
    private TCnemJobRowMapper rowMapper;
    @Autowired
    private TCnemInspLedgerMapper ledgerMapper;
    @Autowired
    private TCnemFeeFlowMapper flowMapper;
    @Autowired
    private TCnemJobOplogMapper oplogMapper;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void reset() {
        CnemTestConfig.resetSchema(dataSource);
    }

    // ---------- 报送 ----------

    @Test
    void submit_systemRanksRows_andHeadOnlyOnce() {
        jobService.submitJob("J1", rows(row(1L, 1, 500), row(1L, 5000, 800)));

        TCnemJob head = jobService.selectByJobNo("J1");
        assertNotNull(head);
        assertEquals(1, head.getStatus().intValue(), "报上去先候着：待审");
        assertEquals(0, head.getRowNo().intValue());
        assertEquals(2, head.getTotalRows().intValue());

        List<TCnemJobRow> rs = rowsOf("J1");
        assertEquals(2, rs.size());
        assertEquals(1, rs.get(0).getRowNo().intValue(), "行次系统从上往下排");
        assertEquals(2, rs.get(1).getRowNo().intValue());
        assertEquals(1, rs.get(0).getStatus().intValue());
    }

    @Test
    void submit_resubmitSameJobNo_doesNotCreateNewRows() {
        List<TCnemJob> first = rows(row(1L, 500, 100), row(1L, 600, 200));
        jobService.submitJob("J2", first);
        String idBefore = rowMapper.selectList(new QueryWrapper<TCnemJobRow>()
                .eq("job_no", "J2")).get(0).getId().toString();

        // 再递一回（内容还不一样）：不另立新行，原行不动
        jobService.submitJob("J2", rows(row(1L, 999, 999)));

        List<TCnemJobRow> rs = rowsOf("J2");
        assertEquals(2, rs.size(), "同作业号在册只容原来的行占住");
        assertEquals(1, jobMapper.selectCount(new QueryWrapper<TCnemJob>()
                .eq("job_no", "J2").eq("row_no", 0)).intValue(), "头只一个");
        assertEquals(idBefore, rs.get(0).getId().toString());
        assertEquals(0, new BigDecimal("100").compareTo(rs.get(0).getAmount()));
    }

    // ---------- 做：判据 ----------

    @Test
    void effect_ruleWithdrawn_expired_missing_future_markRowFailed_othersDone() {
        jobService.submitJob("J3", rows(
                row(1L, 100, 100),    // 启用 → 成
                row(2L, 100, 100),    // 撤下 → 不成
                row(3L, 100, 100),    // 过期 → 不成
                row(null, 100, 100),  // 册上没有 → 不成
                row(999L, 100, 100),  // 指了不存在的 id → 不成
                row(4L, 100, 100)     // 未生效 → 不成
        ));

        int done = jobService.effectJob("J3");
        assertEquals(1, done, "只有启用判据那台做成，其余留在册上写明原因");
        TCnemJob head = jobService.selectByJobNo("J3");
        assertEquals(4, head.getStatus().intValue(), "有成有败：部分生效");
        assertEquals(1, head.getDoneRows().intValue());
        assertEquals(5, head.getFailRows().intValue());

        List<TCnemJobRow> rs = rowsOf("J3");
        assertEquals(4, rs.get(1).getStatus().intValue(), "撤下判据那台没做成");
        assertTrue(rs.get(1).getFailReason().contains("撤下"), rs.get(1).getFailReason());
        assertTrue(rs.get(2).getFailReason().contains("过期"));
        assertTrue(rs.get(3).getFailReason().contains("未指"));
        assertTrue(rs.get(4).getFailReason().contains("册上没有"));
        assertTrue(rs.get(5).getFailReason().contains("尚未生效"));
    }

    @Test
    void effect_fourPlacesSettleTogether_andGradeFromHours() {
        jobService.submitJob("J4", rows(
                row(1L, 100, 100),      // th1=2000 → 等级1
                row(1L, 5000, 200),     // 等级2
                row(1L, 15000, 300)     // 等级3
        ));
        int done = jobService.effectJob("J4");
        assertEquals(3, done);

        List<TCnemInspLedger> ledgers = ledgersOf("J4");
        assertEquals(3, ledgers.size(), "台账每台一条");
        List<TCnemFeeFlow> pos = flowsOf("J4", 1);
        assertEquals(3, pos.size(), "收费正账流水每台一笔");
        assertEquals(3, oplogMapper.selectCount(new QueryWrapper<TCnemJobOplog>()
                .eq("job_no", "J4").eq("action", "row_done")).intValue(), "每台留痕一笔");

        // 作业行 / 台账 / 流水 三处状态、金额对得上
        for (TCnemJobRow r : rowsOf("J4")) {
            assertEquals(3, r.getStatus().intValue());
            assertEquals(1, r.getWritebackFlag().intValue());
        }
        assertEquals(1, ledgers.get(0).getGradeLevel().intValue());
        assertEquals(2, ledgers.get(1).getGradeLevel().intValue());
        assertEquals(3, ledgers.get(2).getGradeLevel().intValue());
        assertEquals(0, ledgers.get(0).getStatus().intValue(), "台账在档");
    }

    @Test
    void effect_terminalHead_cannotBePushedAgain() {
        jobService.submitJob("J5", rows(row(1L, 100, 100), row(2L, 100, 100)));
        assertEquals(1, jobService.effectJob("J5"));
        // 已经做完的再喊一声做，它不理：本次不新做一台，已落定结果不被覆
        assertEquals(0, jobService.effectJob("J5"));
        assertEquals(1, ledgersOf("J5").size());
        // 收回之后也不许再往前做
        assertEquals(1, jobService.rollbackJob("J5"));
        assertEquals(0, jobService.effectJob("J5"));
        assertEquals(6, jobService.selectByJobNo("J5").getStatus().intValue());
    }

    @Test
    void effect_oldDataMissingFields_notBatchReverted() {
        // 老行：没写判据版本、检验编号空、运行小时空（结论等级格也只好空）
        TCnemJob old = row(1L, 100, 100);
        old.setVersion(null);
        old.setInspNo(null);
        old.setQty(null);
        jobService.submitJob("J6", Collections.singletonList(old));

        assertEquals(1, jobService.effectJob("J6"), "老数据缺项不整批回退，接着走");
        TCnemInspLedger l = ledgersOf("J6").get(0);
        assertNull(l.getInspNo());
        assertNull(l.getGradeLevel(), "等级格空着就空着，不编");
    }

    // ---------- 收 ----------

    @Test
    void rollback_keepsLedger_writesOneReverseFlow_andIdempotent() {
        // 两台做成（启用判据）+ 一台没做成（撤下判据）
        jobService.submitJob("J7", rows(row(1L, 100, 300), row(1L, 200, 350), row(2L, 100, 400)));
        jobService.effectJob("J7");
        Long ledgerId = ledgersOf("J7").get(0).getId();

        int back = jobService.rollbackJob("J7");
        assertEquals(2, back, "只收做成过的；没做成的那台不动");

        List<TCnemInspLedger> ledgers = ledgersOf("J7");
        assertEquals(2, ledgers.size(), "收回不是删台账行，做成过的两台台账都还在");
        TCnemInspLedger first = ledgerMapper.selectById(ledgerId);
        assertEquals(ledgerId, first.getId(), "原行还在");
        assertEquals(1, first.getStatus().intValue(), "标成已冲账");

        List<TCnemFeeFlow> rev = flowsOf("J7", 2);
        assertEquals(2, rev.size(), "每台冲账流水各一道");
        assertEquals(0, new BigDecimal("300").negate().compareTo(rev.get(0).getAmount()),
                "冲账金额与正账对顶");

        // 再收一回：不写第二道
        assertEquals(0, jobService.rollbackJob("J7"));
        assertEquals(2, flowsOf("J7", 2).size());
        assertEquals(6, jobService.selectByJobNo("J7").getStatus().intValue());

        List<TCnemJobRow> rs = rowsOf("J7");
        assertEquals(6, rs.get(0).getStatus().intValue());
        assertEquals(6, rs.get(1).getStatus().intValue());
        assertEquals(4, rs.get(2).getStatus().intValue(), "没做成的仍是没做成");
    }

    // ---------- 回查 ----------

    @Test
    void trace_countsStagesAndFlagsHalfAndDuplicate() {
        jobService.submitJob("J8", rows(row(1L, 100, 100), row(1L, 100, 200), row(2L, 100, 300)));
        jobService.effectJob("J8");
        jobService.rollbackJob("J8");

        CnemJobTrace trace = jobService.traceJob("J8");
        assertEquals(0, trace.getSettledRows());
        assertEquals(2, trace.getRolledRows());
        assertEquals(1, trace.getFailedRows());
        assertTrue(trace.getHalfRows().isEmpty(), "四处对齐，没有只落一半");
        assertTrue(trace.getDuplicateReverseRows().isEmpty());

        List<TCnemJobOplog> ops = trace.getOplogs();
        assertTrue(ops.size() >= 6);
        // 倒序：最后一笔（作业头收回完成）在最前
        assertEquals("done", ops.get(0).getAction());
        // 无登录上下文时操作人按 ShiroUtils 口径回落为 Task（生产上是登录用户名）
        assertEquals("Task", ops.get(0).getOperator());
        assertNotNull(ops.get(0).getOpTime());

        // 人为造一笔只落一半：把第 1 台的冲账流水删掉 → 行说收回了，流水缺一笔
        TCnemFeeFlow rev = flowMapper.selectOne(new QueryWrapper<TCnemFeeFlow>()
                .eq("job_no", "J8").eq("row_no", 1).eq("flow_type", 2));
        flowMapper.deleteById(rev.getId());
        CnemJobTrace t2 = jobService.traceJob("J8");
        assertTrue(t2.getHalfRows().contains(1), "倒查得出收到一半的行");

        // 人为造冲账写重（正常库唯一键挡死）：临时撤掉 H2 唯一约束后直插两道，验证查重口径
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement st = c.createStatement()) {
            st.execute("ALTER TABLE t_cnem_fee_flow DROP CONSTRAINT uk_fee_flow_row");
            st.execute("INSERT INTO t_cnem_fee_flow (id, job_no, row_no, amount, flow_type, del_flag) "
                    + "VALUES (-9001, 'J8', 2, -200, 2, 0)");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        CnemJobTrace t3 = jobService.traceJob("J8");
        assertTrue(t3.getDuplicateReverseRows().contains(2), "冲账写重倒查得出来");
    }

    @Test
    void trace_detectsHalfDoneWhenPositiveFlowMissing() {
        jobService.submitJob("J9", rows(row(1L, 100, 100), row(1L, 200, 200)));
        jobService.effectJob("J9");
        // 作业行说做完了，把正账流水抹掉 → 只落一半
        TCnemFeeFlow pos = flowsOf("J9", 1).get(0);
        flowMapper.deleteById(pos.getId());
        // 头已走完生效段，把第 2 台行打回待做、台账抹掉 → 落账异常式半成品
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement st = c.createStatement()) {
            st.execute("UPDATE t_cnem_job_row SET status=1 WHERE job_no='J9' AND row_no=2");
            st.execute("DELETE FROM t_cnem_insp_ledger WHERE job_no='J9' AND row_no=2");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        CnemJobTrace t = jobService.traceJob("J9");
        assertEquals(1, t.getSettledRows());
        assertTrue(t.getHalfRows().contains(1), "做成了却缺正账流水→只落一半");
        assertTrue(t.getHalfRows().contains(2), "头过了生效段行还待做→半成品也倒查得出");
    }

    // ---------- 并发 ----------

    @Test
    void concurrentEffect_onlyFirstWins_noDuplicateSettlement() throws Exception {
        int n = 20;
        List<TCnemJob> rs = new ArrayList<TCnemJob>();
        for (int i = 0; i < n; i++) {
            rs.add(row(1L, 100, 100 + i));
        }
        jobService.submitJob("J10", rs);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger(0);
        List<Throwable> errs = Collections.synchronizedList(new ArrayList<Throwable>());
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    int d = jobService.effectJob("J10");
                    if (d > 0) {
                        winners.incrementAndGet();
                    }
                } catch (Throwable t) {
                    errs.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(errs.isEmpty(), errs.toString());

        // 先到的一笔把 20 台全做完；后到的一笔不得覆写、不得插重
        assertEquals(1, winners.get(), "恰好一笔返回做成数，其余后到的都返回 0（它不理）");
        assertEquals(n, ledgersOf("J10").size(), "每台台账恰一条");
        assertEquals(n, flowsOf("J10", 1).size(), "每台正账恰一笔");
        int status = jobService.selectByJobNo("J10").getStatus().intValue();
        assertTrue(status == 3, "终态为已生效，实际=" + status);
    }

    @Test
    void concurrentSubmit_sameJobNo_onlyOneHeadAndNRows() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errs = Collections.synchronizedList(new ArrayList<Throwable>());
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    jobService.submitJob("J11", rows(row(1L, 100, 100), row(1L, 200, 200)));
                } catch (Throwable t) {
                    errs.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(errs.isEmpty(), errs.toString());

        assertEquals(1, jobMapper.selectCount(new QueryWrapper<TCnemJob>()
                .eq("job_no", "J11").eq("row_no", 0)).intValue());
        assertEquals(2, rowsOf("J11").size());
    }

    // ---------- 辅助 ----------

    private TCnemJob row(Long ruleId, Integer qty, Integer amount) {
        TCnemJob r = new TCnemJob();
        r.setJobName("批量检验");
        r.setRuleId(ruleId);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        r.setAmount(amount == null ? null : new BigDecimal(amount));
        r.setInspNo("INSP-" + Math.abs(amount == null ? 0 : amount) + "-" + (qty == null ? 0 : qty));
        r.setItemCode("DEV-" + amount);
        r.setUnitName("使用单位");
        r.setVersion("IR01");
        return r;
    }

    private List<TCnemJob> rows(TCnemJob... rs) {
        List<TCnemJob> list = new ArrayList<TCnemJob>();
        for (TCnemJob r : rs) {
            list.add(r);
        }
        return list;
    }

    private List<TCnemJobRow> rowsOf(String jobNo) {
        return rowMapper.selectList(new QueryWrapper<TCnemJobRow>()
                .eq("job_no", jobNo).orderByAsc("row_no"));
    }

    private List<TCnemInspLedger> ledgersOf(String jobNo) {
        return ledgerMapper.selectList(new QueryWrapper<TCnemInspLedger>()
                .eq("job_no", jobNo).orderByAsc("row_no"));
    }

    private List<TCnemFeeFlow> flowsOf(String jobNo, int type) {
        return flowMapper.selectList(new QueryWrapper<TCnemFeeFlow>()
                .eq("job_no", jobNo).eq("flow_type", type).orderByAsc("row_no"));
    }
}
