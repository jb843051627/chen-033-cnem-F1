package com.fc.v2.service;

import java.util.List;

import com.fc.v2.model.auto.TCnemJob;
import com.fc.v2.model.auto.TCnemJobOplog;
import com.fc.v2.model.custom.CnemJobTrace;

/**
 * 检验结论作业 Service 接口（job-lifecycle 形状：批量作业报送/做结论/收回，无单条增删改查入口）
 *
 * 三段顺序：报（待审1）→ 做（生效中2）→ 收（回滚中5），照册上口径走，不许跳段。
 *
 * @author fuce
 * @date 2026-10-03
 */
public interface ITCnemJobService {

    /** 按主键回查作业 */
    TCnemJob selectTCnemJobById(Long id);

    /** 按作业号回查作业头（row_no=0） */
    TCnemJob selectByJobNo(String jobNo);

    /**
     * 报送一批：作业号系统在册上排出，同号只占一个作业头；有几台排几行，行次系统从上往下排，
     * 报上去不许自己挪；同号再报一回不另立新行（幂等，原行不动）。
     */
    String submitJob(String jobNo, List<TCnemJob> rows);

    /**
     * 做结论（报→做）：候审过了才轮到做。每台先验判据（撤下/过期/册上没有→做不成，写明原因），
     * 做成的四处同一时刻落定（作业行、台账、收费流水、留痕），少一处不算做完；
     * 哪台没做成只留那台，其余照做。做完/收回完的再喊做不理；同刻压上后到不覆先到。
     * 返回做成台数。
     */
    int effectJob(String jobNo);

    /**
     * 收回（做→收）：不删台账，补冲账流水把落过的一处一处冲回来；同一台冲账流水只准一道。
     * 只收做成过的，没做成/没做过的不动。返回收回台数。
     */
    int rollbackJob(String jobNo);

    /** 倒查：各段经过、落定/没成/收回台数、只落一半的行、冲账写重的行、操作人与时刻 */
    CnemJobTrace traceJob(String jobNo);

    /** 留痕明细（按操作时刻倒序） */
    List<TCnemJobOplog> listOplog(String jobNo);
}
