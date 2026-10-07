package com.fc.v2.model.auto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.Date;

/**
 * 检验作业操作留痕对象 t_cnem_job_oplog
 * 每一次推段/每一台成与不成、谁动的什么时候动的，都留一笔，可倒着查。
 *
 * @author fuce
 * @date 2026-10-07
 */
@TableName("t_cnem_job_oplog")
@ApiModel(value = "TCnemJobOplog", description = "检验作业操作留痕")
public class TCnemJobOplog implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 作业编号 */
    @TableField("job_no")
    @ApiModelProperty(value = "作业编号")
    private String jobNo;

    /** 行次(作业头操作为0) */
    @TableField("row_no")
    @ApiModelProperty(value = "行次(作业头操作为0)")
    private Integer rowNo;

    /** 环节 submit/effect/rollback */
    @TableField("stage")
    @ApiModelProperty(value = "环节 submit/effect/rollback")
    private String stage;

    /** 动作 */
    @TableField("action")
    @ApiModelProperty(value = "动作")
    private String action;

    /** 动前状态 */
    @TableField("from_status")
    @ApiModelProperty(value = "动前状态")
    private Integer fromStatus;

    /** 动后状态 */
    @TableField("to_status")
    @ApiModelProperty(value = "动后状态")
    private Integer toStatus;

    /** 操作人 */
    @TableField("operator")
    @ApiModelProperty(value = "操作人")
    private String operator;

    /** 操作时刻 */
    @TableField("op_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "操作时刻")
    private Date opTime;

    /** 附记(失败原因等) */
    @TableField("note")
    @ApiModelProperty(value = "附记(失败原因等)")
    private String note;

    /** 删除标记 0正常 1删除 */
    @TableField("del_flag")
    @ApiModelProperty(value = "删除标记 0正常 1删除")
    private Integer delFlag;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getJobNo() {
        return jobNo;
    }

    public void setJobNo(String jobNo) {
        this.jobNo = jobNo;
    }

    public Integer getRowNo() {
        return rowNo;
    }

    public void setRowNo(Integer rowNo) {
        this.rowNo = rowNo;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Integer getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(Integer fromStatus) {
        this.fromStatus = fromStatus;
    }

    public Integer getToStatus() {
        return toStatus;
    }

    public void setToStatus(Integer toStatus) {
        this.toStatus = toStatus;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Date getOpTime() {
        return opTime;
    }

    public void setOpTime(Date opTime) {
        this.opTime = opTime;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Integer getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(Integer delFlag) {
        this.delFlag = delFlag;
    }
}
