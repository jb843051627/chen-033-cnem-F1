package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fc.v2.mapper.auto.TCnemFeeFlowMapper;
import com.fc.v2.mapper.auto.TCnemInspLedgerMapper;
import com.fc.v2.mapper.auto.TCnemInspRuleMapper;
import com.fc.v2.mapper.auto.TCnemJobMapper;
import com.fc.v2.mapper.auto.TCnemJobOplogMapper;
import com.fc.v2.mapper.auto.TCnemJobRowMapper;
import com.fc.v2.model.auto.TCnemFeeFlow;
import com.fc.v2.model.auto.TCnemInspLedger;
import com.fc.v2.model.auto.TCnemInspRule;
import com.fc.v2.model.auto.TCnemJob;
import com.fc.v2.model.auto.TCnemJobOplog;
import com.fc.v2.model.auto.TCnemJobRow;
import com.fc.v2.model.custom.CnemJobTrace;
import com.fc.v2.service.ITCnemJobService;
import com.fc.v2.shiro.util.ShiroUtils;

/**
 * 检验结论作业 Service实现（job-lifecycle 形状）
 *
 * 口径（与 doc/schema/cnem.sql 册下口径一致）：
 * 1. 作业号系统在册上排出：同号一个作业头（t_cnem_job.row_no=0），有几台排几行
 *    （t_cnem_job_row.row_no=1..N），行次系统从上往下排，报上去不许挪；同号再报不另立新行。
 * 2. 三段：报(1待审) → 做(2生效中→3已生效/4部分生效) → 收(5回滚中→6已回滚)。
 *    头状态靠 CAS（update ... where status=?）推进，跳段/同刻并发都只有先到的一笔能成。
 * 3. 做一台四处同一刻落定（逐行独立事务，行级 for update）：作业行=做完、台账一条、
 *    收费正账流水一笔、留痕一笔；任一没落上整台回回，不算做完。判据撤了/过期了/册上没有，
 *    这台判没做成（行=4 写明原因），其余照做，不推翻整批。
 * 4. 收回不删台账：台账标已冲账，补一笔负的冲账流水，同一台冲账只准一道（库唯一键兜底）。
 *
 * @author fuce
 * @date 2026-10-03
 */
@Service
public class TCnemJobServiceImpl implements ITCnemJobService {

    // ===== 作业头状态机（草稿→待审→生效中→已生效/部分生效→回滚中→已回滚）=====
    private static final int ST_DRAFT = 0;
    private static final int ST_PENDING = 1;
    private static final int ST_EFFECTING = 2;
    private static final int ST_EFFECTED = 3;
    private static final int ST_PARTIAL = 4;
    private static final int ST_ROLLING = 5;
    private static final int ST_ROLLED = 6;

    // ===== 作业行状态（只取头状态里的 1/3/4/6，不经过 0/2/5）=====
    private static final int ROW_PENDING = ST_PENDING;
    private static final int ROW_DONE = ST_EFFECTED;
    private static final int ROW_FAIL = ST_PARTIAL;
    private static final int ROW_ROLLED = ST_ROLLED;

    private static final int LEDGER_ON = 0;
    private static final int LEDGER_REVERSED = 1;
    private static final int FLOW_POSITIVE = 1;
    private static final int FLOW_REVERSE = 2;

    @Autowired
    private TCnemJobMapper cnemJobMapper;
    @Autowired
    private TCnemJobRowMapper cnemJobRowMapper;
    @Autowired
    private TCnemInspRuleMapper cnemInspRuleMapper;
    @Autowired
    private TCnemInspLedgerMapper cnemInspLedgerMapper;
    @Autowired
    private TCnemFeeFlowMapper cnemFeeFlowMapper;
    @Autowired
    private TCnemJobOplogMapper cnemJobOplogMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txNew() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    @Override
    public TCnemJob selectTCnemJobById(Long id) {
        if (id == null) {
            return null;
        }
        return this.cnemJobMapper.selectById(id);
    }

    @Override
    public TCnemJob selectByJobNo(String jobNo) {
        if (jobNo == null) {
            return null;
        }
        return this.cnemJobMapper.selectOne(new QueryWrapper<TCnemJob>()
                .eq("job_no", jobNo).eq("row_no", 0).eq("del_flag", 0).last("limit 1"));
    }

    // ================================================================= 报 ===

    @Override
    public String submitJob(final String jobNo, final List<TCnemJob> rows) {
        if (jobNo == null || jobNo.trim().isEmpty() || rows == null || rows.isEmpty()) {
            return jobNo;
        }
        // 同一作业号在册只容一个头占住：头已在 → 再递一回也不另立新行，原行不动
        if (selectByJobNo(jobNo) != null) {
            return jobNo;
        }
        try {
            txNew().execute(status -> {
                TCnemJob head = new TCnemJob();
                head.setJobNo(jobNo);
                head.setJobName(rows.get(0).getJobName());
                head.setStatus(Integer.valueOf(ST_PENDING));
                head.setRowNo(Integer.valueOf(0));
                head.setTotalRows(Integer.valueOf(rows.size()));
                head.setDoneRows(Integer.valueOf(0));
                head.setFailRows(Integer.valueOf(0));
                head.setWritebackFlag(Integer.valueOf(0));
                head.setCompensated(Integer.valueOf(0));
                head.setDelFlag(Integer.valueOf(0));
                this.cnemJobMapper.insert(head);

                // 有几台就是几行，行次系统从上往下排（报送人手里没有填号的格子）
                int idx = 0;
                for (TCnemJob in : rows) {
                    idx++;
                    TCnemJobRow r = new TCnemJobRow();
                    r.setJobNo(jobNo);
                    r.setRowNo(Integer.valueOf(idx));
                    r.setInspNo(in.getInspNo());
                    r.setItemCode(in.getItemCode());
                    r.setUnitName(in.getUnitName());
                    r.setQty(in.getQty());
                    r.setRuleId(in.getRuleId());
                    r.setAmount(in.getAmount());
                    r.setJobQty(in.getJobQty());
                    r.setVersion(in.getVersion());
                    r.setVersionStatus(in.getVersionStatus());
                    r.setWritebackFlag(Integer.valueOf(0));
                    r.setCompensateFlag(Integer.valueOf(0));
                    r.setCompensated(Integer.valueOf(0));
                    r.setStatus(Integer.valueOf(ROW_PENDING));
                    r.setDelFlag(Integer.valueOf(0));
                    this.cnemJobRowMapper.insert(r);
                }
                oplog(jobNo, 0, "submit", "submitted", null, ST_PENDING, "报送 " + idx + " 台，候审批");
                return null;
            });
        } catch (DuplicateKeyException dk) {
            // 并发两笔同号报送，唯一键兜底：后到的不另立，认先到的
            return jobNo;
        } catch (Exception e) {
            // 手工装配/驱动差异下唯一键冲突可能没被转译成 DuplicateKeyException：
            // 此刻头若已被先到的一笔占住，同样按「不另立」处理；否则是真故障，抛回去
            if (selectByJobNo(jobNo) != null) {
                return jobNo;
            }
            throw e;
        }
        return jobNo;
    }

    // ================================================================ 做 ====

    @Override
    public int effectJob(final String jobNo) {
        final TCnemJob head = selectByJobNo(jobNo);
        if (head == null) {
            return 0;
        }
        // 报了先候着：只有待审(1)能推进；做完/部分做完/收回的再喊做不理；
        // 同刻压上的两笔只有先到一笔 CAS 成功，后到的不得把已定结果覆掉
        final Date now = new Date();
        int grabbed = this.cnemJobMapper.update(null, new LambdaUpdateWrapper<TCnemJob>()
                .eq(TCnemJob::getId, head.getId())
                .eq(TCnemJob::getRowNo, 0)
                .eq(TCnemJob::getStatus, ST_PENDING)
                .set(TCnemJob::getStatus, ST_EFFECTING)
                .set(TCnemJob::getEffStart, now));
        if (grabbed == 0) {
            // 不处在待审段（做完的/部分做完的/收回中的/已收回的）：它不理，本次不新做一台
            return 0;
        }
        oplog(jobNo, 0, "effect", "begin", ST_PENDING, ST_EFFECTING, "审批过，开始做结论");

        List<TCnemJobRow> rows = listRows(jobNo);
        int done = 0;
        int fail = 0;
        for (TCnemJobRow r : rows) {
            String outcome;
            try {
                outcome = txNew().execute(st -> effectOne(r, new Date()));
            } catch (Exception e) {
                // 落库类异常：这一台四处没对齐，整台回回，仍候着（行回到待做），不拖垮其余台
                outcome = "ERROR";
                safeOplog(jobNo, r.getRowNo().intValue(), "effect", "row_error",
                        ROW_PENDING, ROW_PENDING, "落账异常已整台回回: " + rootMsg(e));
            }
            if ("DONE".equals(outcome)) {
                done++;
            } else if ("FAIL".equals(outcome)) {
                fail++;
            }
            // ERROR / SKIP 不计终态
        }

        final int doneCnt = done;
        final int failCnt = fail;
        final int headStatus = doneCnt > 0 && failCnt == 0 ? ST_EFFECTED : ST_PARTIAL;
        this.cnemJobMapper.update(null, new LambdaUpdateWrapper<TCnemJob>()
                .eq(TCnemJob::getId, head.getId())
                .eq(TCnemJob::getStatus, ST_EFFECTING)
                .set(TCnemJob::getStatus, headStatus)
                .set(TCnemJob::getDoneRows, doneCnt)
                .set(TCnemJob::getFailRows, failCnt)
                .set(TCnemJob::getEffEnd, new Date())
                .set(TCnemJob::getWritebackFlag, doneCnt > 0 ? 1 : 0));
        oplog(jobNo, 0, "effect", "done", ST_EFFECTING, headStatus,
                "落定 " + doneCnt + " 台，没做成 " + failCnt + " 台");
        return doneCnt;
    }

    /** 一台的四处落定；在行级锁内执行，返回 DONE/FAIL/SKIP。判据不过是业务失败(FAIL)，落册不抛批。 */
    private String effectOne(TCnemJobRow row, Date now) {
        TCnemJobRow locked = this.cnemJobRowMapper.selectOne(new QueryWrapper<TCnemJobRow>()
                .eq("id", row.getId()).eq("del_flag", 0).last("for update"));
        if (locked == null || locked.getStatus() == null
                || locked.getStatus().intValue() != ROW_PENDING) {
            // 已经做成/没做成/收回的，不理
            return "SKIP";
        }

        // 做之前先看判据：撤了、过期了、册上压根没有（含老行没指），这台做不成
        String ruleProblem = checkRule(locked.getRuleId(), now);
        if (ruleProblem != null) {
            locked.setStatus(Integer.valueOf(ROW_FAIL));
            locked.setWritebackFlag(Integer.valueOf(0));
            locked.setFailReason(ruleProblem);
            this.cnemJobRowMapper.updateById(locked);
            oplog(locked.getJobNo(), locked.getRowNo().intValue(), "effect", "row_fail",
                    ROW_PENDING, ROW_FAIL, ruleProblem);
            return "FAIL";
        }
        TCnemInspRule rule = this.cnemInspRuleMapper.selectById(locked.getRuleId());
        Integer level = gradeLevel(rule, locked.getQty());

        // 四处同一时刻落定：作业行 + 台账 + 收费正账流水 + 留痕，少一处整台回回
        locked.setStatus(Integer.valueOf(ROW_DONE));
        locked.setWritebackFlag(Integer.valueOf(1));
        locked.setFailReason(null);
        if (locked.getVersion() == null) {
            locked.setVersion(rule.getRuleCode());
        }
        locked.setVersionStatus(Integer.valueOf(1));
        this.cnemJobRowMapper.updateById(locked);

        TCnemInspLedger ledger = new TCnemInspLedger();
        ledger.setJobNo(locked.getJobNo());
        ledger.setRowNo(locked.getRowNo());
        ledger.setInspNo(locked.getInspNo());
        ledger.setItemCode(locked.getItemCode());
        ledger.setUnitName(locked.getUnitName());
        ledger.setGradeLevel(level);
        ledger.setRuleId(rule.getId());
        ledger.setVersion(locked.getVersion());
        ledger.setAmount(locked.getAmount());
        ledger.setStatus(Integer.valueOf(LEDGER_ON));
        ledger.setDelFlag(Integer.valueOf(0));
        this.cnemInspLedgerMapper.insert(ledger);

        TCnemFeeFlow flow = new TCnemFeeFlow();
        flow.setJobNo(locked.getJobNo());
        flow.setRowNo(locked.getRowNo());
        flow.setInspNo(locked.getInspNo());
        flow.setItemNo(locked.getInspNo());
        flow.setAmount(locked.getAmount());
        flow.setFlowType(Integer.valueOf(FLOW_POSITIVE));
        flow.setDelFlag(Integer.valueOf(0));
        this.cnemFeeFlowMapper.insert(flow);

        oplog(locked.getJobNo(), locked.getRowNo().intValue(), "effect", "row_done",
                ROW_PENDING, ROW_DONE,
                "四处落定（作业行/台账/收费流水/留痕），结论等级=" + (level == null ? "空(老数据缺项)" : level));
        return "DONE";
    }

    /** 判据校验。没问题返 null；有问题返写明的原因。 */
    private String checkRule(Long ruleId, Date now) {
        if (ruleId == null) {
            return "判据这边出了状况：该行未指检验判据（册上没有可依的条）";
        }
        TCnemInspRule rule = this.cnemInspRuleMapper.selectById(ruleId);
        if (rule == null || (rule.getDelFlag() != null && rule.getDelFlag().intValue() == 1)) {
            return "判据这边出了状况：所指判据册上没有（或已删除）";
        }
        if (rule.getStatus() != null && rule.getStatus().intValue() == 1) {
            return "判据这边出了状况：所指判据已撤下，不认";
        }
        if (rule.getEffStart() != null && now.before(rule.getEffStart())) {
            return "判据这边出了状况：所指判据尚未生效";
        }
        if (rule.getEffEnd() != null && !now.before(rule.getEffEnd())) {
            return "判据这边出了状况：所指判据已过期（生效截止时刻不含）";
        }
        return null;
    }

    /** 分档口径与判据册一致：≤th1 一档、≤th2 二档、≤th3 三档、否则四档；阈值缺/小时缺返 null（老数据缺项不拦） */
    private Integer gradeLevel(TCnemInspRule rule, BigDecimal qty) {
        if (rule == null || qty == null) {
            return null;
        }
        if (rule.getTh1Max() != null && qty.compareTo(rule.getTh1Max()) <= 0) {
            return Integer.valueOf(1);
        }
        if (rule.getTh2Max() != null && qty.compareTo(rule.getTh2Max()) <= 0) {
            return Integer.valueOf(2);
        }
        if (rule.getTh3Max() != null && qty.compareTo(rule.getTh3Max()) <= 0) {
            return Integer.valueOf(3);
        }
        return Integer.valueOf(4);
    }

    // ================================================================ 收 ====

    @Override
    public int rollbackJob(final String jobNo) {
        final TCnemJob head = selectByJobNo(jobNo);
        if (head == null) {
            return 0;
        }
        // 只有做完(3)/部分做完(4)才轮到收；没收完一半不许越过，没做过/已收回再喊不理
        int grabbed = this.cnemJobMapper.update(null, new LambdaUpdateWrapper<TCnemJob>()
                .eq(TCnemJob::getId, head.getId())
                .eq(TCnemJob::getRowNo, 0)
                .in(TCnemJob::getStatus, Integer.valueOf(ST_EFFECTED), Integer.valueOf(ST_PARTIAL))
                .set(TCnemJob::getStatus, ST_ROLLING));
        if (grabbed == 0) {
            // 不处在可收段（没做过/收到一半/已收回）：不理，本次不新收一台
            return 0;
        }
        oplog(jobNo, 0, "rollback", "begin", head.getStatus().intValue(), ST_ROLLING, "开始收回已做成的结论");

        List<TCnemJobRow> rows = listRows(jobNo);
        int back = 0;
        for (TCnemJobRow r : rows) {
            try {
                String outcome = txNew().execute(st -> rollbackOne(r));
                if ("DONE".equals(outcome)) {
                    back++;
                }
            } catch (Exception e) {
                safeOplog(jobNo, r.getRowNo().intValue(), "rollback", "row_error",
                        ROW_DONE, ROW_DONE, "冲账异常未收回: " + rootMsg(e));
            }
        }

        this.cnemJobMapper.update(null, new LambdaUpdateWrapper<TCnemJob>()
                .eq(TCnemJob::getId, head.getId())
                .eq(TCnemJob::getStatus, ST_ROLLING)
                .set(TCnemJob::getStatus, ST_ROLLED)
                .set(TCnemJob::getCompensated, Integer.valueOf(1))
                .set(TCnemJob::getCompensateFlag, Integer.valueOf(1)));
        oplog(jobNo, 0, "rollback", "done", ST_ROLLING, ST_ROLLED,
                "收回 " + back + " 台；台账未删，均补冲账流水且每台仅一道");
        return back;
    }

    /** 收回一台：台账不删只标冲账 + 负的冲账流水 + 作业行收回 + 留痕；DONE/SKIP。 */
    private String rollbackOne(TCnemJobRow row) {
        TCnemJobRow locked = this.cnemJobRowMapper.selectOne(new QueryWrapper<TCnemJobRow>()
                .eq("id", row.getId()).eq("del_flag", 0).last("for update"));
        if (locked == null || locked.getStatus() == null
                || locked.getStatus().intValue() != ROW_DONE) {
            // 没做成的、没做过的、已收回的，不动
            return "SKIP";
        }

        TCnemInspLedger ledger = this.cnemInspLedgerMapper.selectOne(new QueryWrapper<TCnemInspLedger>()
                .eq("job_no", locked.getJobNo()).eq("row_no", locked.getRowNo())
                .last("for update"));
        TCnemFeeFlow positive = this.cnemFeeFlowMapper.selectOne(new QueryWrapper<TCnemFeeFlow>()
                .eq("job_no", locked.getJobNo()).eq("row_no", locked.getRowNo())
                .eq("flow_type", FLOW_POSITIVE).last("limit 1"));
        TCnemFeeFlow reverse = this.cnemFeeFlowMapper.selectOne(new QueryWrapper<TCnemFeeFlow>()
                .eq("job_no", locked.getJobNo()).eq("row_no", locked.getRowNo())
                .eq("flow_type", FLOW_REVERSE).last("limit 1"));

        // 把落过的地方一处一处冲回来；正账没落的不凭空补冲账
        if (ledger != null && ledger.getStatus() != null
                && ledger.getStatus().intValue() == LEDGER_ON) {
            ledger.setStatus(Integer.valueOf(LEDGER_REVERSED));
            this.cnemInspLedgerMapper.updateById(ledger);
        }
        if (positive != null && reverse == null) {
            TCnemFeeFlow rf = new TCnemFeeFlow();
            rf.setJobNo(locked.getJobNo());
            rf.setRowNo(locked.getRowNo());
            rf.setInspNo(locked.getInspNo());
            rf.setItemNo(locked.getInspNo());
            rf.setAmount(positive.getAmount() == null ? null : positive.getAmount().negate());
            rf.setFlowType(Integer.valueOf(FLOW_REVERSE));
            rf.setDelFlag(Integer.valueOf(0));
            this.cnemFeeFlowMapper.insert(rf);
        }

        locked.setStatus(Integer.valueOf(ROW_ROLLED));
        locked.setCompensated(Integer.valueOf(1));
        locked.setCompensateFlag(Integer.valueOf(1));
        this.cnemJobRowMapper.updateById(locked);
        oplog(locked.getJobNo(), locked.getRowNo().intValue(), "rollback", "row_rolled",
                ROW_DONE, ROW_ROLLED, "台账标冲账+冲账流水一道，结论行未删");
        return "DONE";
    }

    // ================================================================ 查 ====

    @Override
    public CnemJobTrace traceJob(String jobNo) {
        CnemJobTrace trace = new CnemJobTrace();
        trace.setJob(selectByJobNo(jobNo));
        if (trace.getJob() == null) {
            return trace;
        }
        List<TCnemJobRow> rows = listRows(jobNo);
        trace.setRows(rows);

        List<TCnemInspLedger> ledgers = this.cnemInspLedgerMapper.selectList(
                new QueryWrapper<TCnemInspLedger>().eq("job_no", jobNo).eq("del_flag", 0));
        List<TCnemFeeFlow> flows = this.cnemFeeFlowMapper.selectList(
                new QueryWrapper<TCnemFeeFlow>().eq("job_no", jobNo).eq("del_flag", 0));
        trace.setOplogs(listOplog(jobNo));

        Map<Integer, TCnemInspLedger> ledgerByRow = new HashMap<Integer, TCnemInspLedger>();
        for (TCnemInspLedger l : ledgers) {
            ledgerByRow.put(l.getRowNo(), l);
        }
        Map<Integer, List<TCnemFeeFlow>> flowByRow = new HashMap<Integer, List<TCnemFeeFlow>>();
        for (TCnemFeeFlow f : flows) {
            List<TCnemFeeFlow> list = flowByRow.get(f.getRowNo());
            if (list == null) {
                list = new ArrayList<TCnemFeeFlow>();
                flowByRow.put(f.getRowNo(), list);
            }
            list.add(f);
        }

        int settled = 0;
        int rolled = 0;
        int failed = 0;
        List<Integer> half = new ArrayList<Integer>();
        List<Integer> dupReverse = new ArrayList<Integer>();
        for (TCnemJobRow r : rows) {
            int rn = r.getRowNo().intValue();
            int st = r.getStatus() == null ? -1 : r.getStatus().intValue();
            TCnemInspLedger l = ledgerByRow.get(Integer.valueOf(rn));
            List<TCnemFeeFlow> fs = flowByRow.get(Integer.valueOf(rn));
            int pos = countFlow(fs, FLOW_POSITIVE);
            int rev = countFlow(fs, FLOW_REVERSE);
            if (rev > 1) {
                dupReverse.add(Integer.valueOf(rn));
            }
            if (st == ROW_DONE) {
                settled++;
                // 说做完了：台账必须在档、正账流水必须有，否则就是只落一半
                if (l == null || l.getStatus() == null || l.getStatus().intValue() != LEDGER_ON
                        || pos < 1) {
                    half.add(Integer.valueOf(rn));
                }
            } else if (st == ROW_ROLLED) {
                rolled++;
                // 说收回了：台账必须已冲账、冲账流水必须正好一道，否则就是收到一半/冲账写重
                if (l == null || l.getStatus() == null
                        || l.getStatus().intValue() != LEDGER_REVERSED || rev != 1) {
                    half.add(Integer.valueOf(rn));
                }
            } else if (st == ROW_FAIL) {
                failed++;
            } else if (st == ROW_PENDING) {
                // 头已走过/走完生效段，行还停在待做（落账异常整台回回留下的）：算半成品；
                // 头还在待审/生效中时，行待做是正常在途，不算
                int hs = trace.getJob().getStatus() == null ? -1 : trace.getJob().getStatus().intValue();
                if (hs == ST_EFFECTED || hs == ST_PARTIAL || hs == ST_ROLLING || hs == ST_ROLLED) {
                    half.add(Integer.valueOf(rn));
                }
            }
        }
        trace.setSettledRows(settled);
        trace.setRolledRows(rolled);
        trace.setFailedRows(failed);
        trace.setHalfRows(half);
        trace.setDuplicateReverseRows(dupReverse);
        return trace;
    }

    @Override
    public List<TCnemJobOplog> listOplog(String jobNo) {
        List<TCnemJobOplog> list = this.cnemJobOplogMapper.selectList(
                new QueryWrapper<TCnemJobOplog>().eq("job_no", jobNo).eq("del_flag", 0));
        // 倒着查：时刻新的在前；同刻压上的按行次大的在前（后到的先看见），顺序确定
        Collections.sort(list, new Comparator<TCnemJobOplog>() {
            @Override
            public int compare(TCnemJobOplog a, TCnemJobOplog b) {
                Date ta = a.getOpTime() == null ? new Date(0) : a.getOpTime();
                Date tb = b.getOpTime() == null ? new Date(0) : b.getOpTime();
                int c = tb.compareTo(ta);
                if (c != 0) {
                    return c;
                }
                int ra = a.getRowNo() == null ? 0 : a.getRowNo().intValue();
                int rb = b.getRowNo() == null ? 0 : b.getRowNo().intValue();
                return rb - ra;
            }
        });
        return list;
    }

    // ============================================================= 辅助 ====

    private List<TCnemJobRow> listRows(String jobNo) {
        List<TCnemJobRow> rows = this.cnemJobRowMapper.selectList(new QueryWrapper<TCnemJobRow>()
                .eq("job_no", jobNo).eq("del_flag", 0));
        Collections.sort(rows, new Comparator<TCnemJobRow>() {
            @Override
            public int compare(TCnemJobRow a, TCnemJobRow b) {
                int ra = a.getRowNo() == null ? 0 : a.getRowNo().intValue();
                int rb = b.getRowNo() == null ? 0 : b.getRowNo().intValue();
                return ra - rb;
            }
        });
        return rows;
    }

    private int countFlow(List<TCnemFeeFlow> flows, int type) {
        if (flows == null) {
            return 0;
        }
        int n = 0;
        for (TCnemFeeFlow f : flows) {
            if (f.getFlowType() != null && f.getFlowType().intValue() == type) {
                n++;
            }
        }
        return n;
    }

    /** 留痕一笔：谁动的、什么时候、哪一台、从什么状态推到什么状态。随调用方事务一同落定。 */
    private void oplog(String jobNo, int rowNo, String stage, String action,
                       Integer fromStatus, Integer toStatus, String note) {
        TCnemJobOplog op = new TCnemJobOplog();
        op.setJobNo(jobNo);
        op.setRowNo(Integer.valueOf(rowNo));
        op.setStage(stage);
        op.setAction(action);
        op.setFromStatus(fromStatus);
        op.setToStatus(toStatus);
        op.setOperator(operator());
        op.setOpTime(new Date());
        op.setNote(note);
        op.setDelFlag(Integer.valueOf(0));
        this.cnemJobOplogMapper.insert(op);
    }

    /** 独立事务补一笔留痕（本行主事务已整台回回时用，保证异常也倒查得到） */
    private void safeOplog(final String jobNo, final int rowNo, final String stage, final String action,
                           final int fromStatus, final int toStatus, final String note) {
        try {
            txNew().execute(st -> {
                oplog(jobNo, rowNo, stage, action, fromStatus, toStatus, note);
                return null;
            });
        } catch (Exception ignore) {
            // 留痕自身失败不再抛出，免得把批次循环打断
        }
    }

    private String operator() {
        try {
            return ShiroUtils.getLoginName();
        } catch (Exception e) {
            return "system";
        }
    }

    private String rootMsg(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : m;
    }
}
