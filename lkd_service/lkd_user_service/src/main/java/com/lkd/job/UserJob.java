package com.lkd.job;
import com.lkd.common.VMSystem;
import com.lkd.service.UserService;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.annotation.XxlJob;
import com.xxl.job.core.log.XxlJobLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;


@Component
@Slf4j
public class UserJob {

    @Autowired
    private UserService userService;

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 每日工单量列表初始化
     * @param param
     * @return
     * @throws Exception
     */
    @XxlJob("workCountInitJobHandler")
    public ReturnT<String> workCountInitJobHandler(String param) {
        try{
            XxlJobLogger.log("每日工单量列表初始化");
            //工单初始化
            userService.list().forEach(user -> {
                if(!user.getRoleCode().equals("1001")){   //只考虑非管理员
                    String key= VMSystem.REGION_TASK_KEY_PREF
                            + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))   //日期
                            +"."+ user.getRegionId()   //区域
                            +"."+user.getRoleCode();   //角色code
                    redisTemplate.opsForZSet().add(key,user.getId(),0 ) ;
                    redisTemplate.expire(key, Duration.ofDays(1));
                }
            });
            return ReturnT.SUCCESS;
        }catch (Exception e){
            return ReturnT.FAIL;
        }
    }

}