package com.lkd.service;
import com.baomidou.mybatisplus.extension.service.IService;
import com.lkd.entity.TaskEntity;
import com.lkd.entity.TaskStatusTypeEntity;
import com.lkd.exception.LogicException;
import com.lkd.http.vo.CancelTaskViewModel;
import com.lkd.http.vo.TaskViewModel;
import com.lkd.vo.Pager;

import java.util.List;

/**
 * 工单业务逻辑
 */
public interface TaskService extends IService<TaskEntity> {


    /**
     * 通过条件搜索工单列表
     * @param pageIndex
     * @param pageSize
     * @param innerCode
     * @param userId
     * @param taskCode
     * @param isRepair 是否是运维工单
     * @return
     */
    Pager<TaskEntity> search(Long pageIndex, Long pageSize, String innerCode, Integer userId, String taskCode, Integer status, Boolean isRepair, String start, String end);





    /**
     * 获取所有状态类型
     * @return
     */
    List<TaskStatusTypeEntity> getAllStatus();



    /**
     * 创建工单
     * @param taskViewModel
     * @return
     */
    boolean createTask(TaskViewModel taskViewModel) throws LogicException;


    /**
     * 接受工单
     * @param id
     * @return
     */
    boolean accept(Long id);

    /**
     * 取消工单
     * @param id
     * @param cancelVM
     * @return
     */
    boolean cancelTask(Long id, CancelTaskViewModel cancelVM);


    /**
     * 完成工单
     * @param id
     * @return
     */
    boolean completeTask(long id);


    /**
     * 获取同一天内分配的工单最少的人
     * @param regionId
     * @param isRepair 是否是运维工单
     * @return
     */
    int getLeastUser(Long regionId,Boolean isRepair);


}
