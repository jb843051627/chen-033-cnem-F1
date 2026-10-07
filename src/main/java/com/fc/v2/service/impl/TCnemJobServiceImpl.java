package com.fc.v2.service.impl;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TCnemJobMapper;
import com.fc.v2.model.auto.TCnemJob;
import com.fc.v2.service.ITCnemJobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 检验结论作业 Service实现（job-lifecycle 形状：批量作业提交/生效/回滚，跨模块协调）
 *
 * @author fuce
 * @date 2026-10-03
 */
@Service
public class TCnemJobServiceImpl implements ITCnemJobService {

    @Autowired
    private TCnemJobMapper cnemJobMapper;

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

    @Override
    public String submitJob(String jobNo, List<TCnemJob> rows) {
        TCnemJob job = new TCnemJob();
        job.setJobNo(jobNo);
        job.setStatus(Integer.valueOf(ST_PENDING));
        job.setRowNo(Integer.valueOf(0));
        this.cnemJobMapper.insert(job);
        int idx = 0;
        for (TCnemJob r : rows) {
            idx++;
            r.setJobNo(jobNo);
            r.setRowNo(Integer.valueOf(idx));
            r.setStatus(Integer.valueOf(ST_DRAFT));
            // D6 基线：存量脏数据直接抛，炸整批
            if (r.getInspNo() == null || r.getAmount() == null) {
                throw new IllegalArgumentException("行数据不完整: " + idx);
            }
            this.cnemJobMapper.insert(r);
        }
        return jobNo;
    }

    @Override
    public int effectJob(String jobNo) {
        TCnemJob job0 = this.cnemJobMapper.selectOne(new QueryWrapper<TCnemJob>()
                .eq("job_no", jobNo).eq("del_flag", 0));
        if (job0 == null) {
            return 0;
        }
        // D1 基线：无终态守卫，已生效/已回滚的作业也能再次推进
        job0.setStatus(Integer.valueOf(ST_EFFECTED));
        this.cnemJobMapper.updateById(job0);
        List<TCnemJob> items = this.cnemJobMapper.selectList(new QueryWrapper<TCnemJob>()
                .eq("job_no", jobNo).eq("del_flag", 0));
        int done = 0;
        for (TCnemJob r : items) {
            // D4 基线：无版本锁，撤下/过期版本也写回
            // D5 基线：只落主表，流水/留痕不写；无部分成功隔离，一行抛错整批中断
            r.setWritebackFlag(Integer.valueOf(1));
            this.cnemJobMapper.updateById(r);
            r.setStatus(Integer.valueOf(ST_EFFECTED));
            this.cnemJobMapper.updateById(r);
            done++;
        }
        job0.setDoneRows(Integer.valueOf(done));
        return done;
    }

    @Override
    public int rollbackJob(String jobNo) {
        TCnemJob job1 = this.cnemJobMapper.selectOne(new QueryWrapper<TCnemJob>()
                .eq("job_no", jobNo).eq("del_flag", 0));
        if (job1 == null) {
            return 0;
        }
        List<TCnemJob> items = this.cnemJobMapper.selectList(new QueryWrapper<TCnemJob>()
                .eq("job_no", jobNo).eq("del_flag", 0));
        int back = 0;
        for (TCnemJob r : items) {
            // D6 基线：无补偿去重，回滚几次就写几次补偿流水（重试不幂等）
            int cc = r.getCompensateCnt() == null ? 0 : r.getCompensateCnt().intValue();
            r.setCompensateCnt(Integer.valueOf(cc + 1));
            r.setCompensateFlag(Integer.valueOf(1));
            r.setStatus(Integer.valueOf(ST_ROLLED));
            this.cnemJobMapper.updateById(r);
            back++;
        }
        job1.setStatus(Integer.valueOf(ST_ROLLED));
        this.cnemJobMapper.updateById(job1);
        return back;
    }

    // 作业状态机常量（草稿→待审→生效中→已生效/部分生效→回滚中→已回滚）
    private static final int ST_DRAFT = 0;
    private static final int ST_PENDING = 1;
    private static final int ST_EFFECTING = 2;
    private static final int ST_EFFECTED = 3;
    private static final int ST_PARTIAL = 4;
    private static final int ST_ROLLING = 5;
    private static final int ST_ROLLED = 6;

    private static java.text.SimpleDateFormat _fmt() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    }

    private static String ts(java.util.Date d) {
        return d == null ? "" : _fmt().format(d);
    }

    /** 跨模块写回：基线只落业务主表（流水/留痕缺失 -> 写回不守恒） */
    private void writeback(TCnemJob r) {
        if (r == null) {
            return;
        }
        r.setWritebackFlag(Integer.valueOf(1));
        this.cnemJobMapper.updateById(r);
    }

    /** 写补偿流水：基线也累计次数（缺陷在调用侧不去重） */
    private void compensate(TCnemJob r) {
        if (r == null) {
            return;
        }
        int c = r.getCompensateCnt() == null ? 0 : r.getCompensateCnt().intValue();
        r.setCompensateCnt(Integer.valueOf(c + 1));
        r.setCompensateFlag(Integer.valueOf(1));
        this.cnemJobMapper.updateById(r);
    }
}
