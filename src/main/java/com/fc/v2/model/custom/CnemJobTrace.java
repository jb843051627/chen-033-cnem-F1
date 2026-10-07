package com.fc.v2.model.custom;

import com.fc.v2.model.auto.TCnemJob;
import com.fc.v2.model.auto.TCnemJobOplog;
import com.fc.v2.model.auto.TCnemJobRow;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 检验作业回查轨迹（倒查口径）：
 * 这一批走过哪一段、落定几台、没落成几台、收回几台、冲账写没写重、有没有只落一半，
 * 谁动的、什么时候动的，都在这一张上。
 *
 * @author fuce
 * @date 2026-10-07
 */
public class CnemJobTrace implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 作业头（含当前段与计数） */
    private TCnemJob job;

    /** 作业行（按行次升序） */
    private List<TCnemJobRow> rows = new ArrayList<TCnemJobRow>();

    /** 留痕（按操作时刻倒序，同刻按行次倒序——后到的排在前头便于倒查） */
    private List<TCnemJobOplog> oplogs = new ArrayList<TCnemJobOplog>();

    /** 落定台数（台账在档 status=0） */
    private int settledRows;

    /** 没落成台数（作业行 status=4） */
    private int failedRows;

    /** 收回台数（台账已冲账 status=1） */
    private int rolledRows;

    /** 只落一半的行次：作业行说做完/收回了，但台账或流水缺一笔 */
    private List<Integer> halfRows = new ArrayList<Integer>();

    /** 冲账流水写重了的行次（同一台出现两道以上冲账；正常库唯一键挡死，脏数据可倒查出来） */
    private List<Integer> duplicateReverseRows = new ArrayList<Integer>();

    public TCnemJob getJob() {
        return job;
    }

    public void setJob(TCnemJob job) {
        this.job = job;
    }

    public List<TCnemJobRow> getRows() {
        return rows;
    }

    public void setRows(List<TCnemJobRow> rows) {
        this.rows = rows;
    }

    public List<TCnemJobOplog> getOplogs() {
        return oplogs;
    }

    public void setOplogs(List<TCnemJobOplog> oplogs) {
        this.oplogs = oplogs;
    }

    public int getSettledRows() {
        return settledRows;
    }

    public void setSettledRows(int settledRows) {
        this.settledRows = settledRows;
    }

    public int getFailedRows() {
        return failedRows;
    }

    public void setFailedRows(int failedRows) {
        this.failedRows = failedRows;
    }

    public int getRolledRows() {
        return rolledRows;
    }

    public void setRolledRows(int rolledRows) {
        this.rolledRows = rolledRows;
    }

    public List<Integer> getHalfRows() {
        return halfRows;
    }

    public void setHalfRows(List<Integer> halfRows) {
        this.halfRows = halfRows;
    }

    public List<Integer> getDuplicateReverseRows() {
        return duplicateReverseRows;
    }

    public void setDuplicateReverseRows(List<Integer> duplicateReverseRows) {
        this.duplicateReverseRows = duplicateReverseRows;
    }
}
