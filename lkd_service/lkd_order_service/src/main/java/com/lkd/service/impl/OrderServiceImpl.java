package com.lkd.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.lkd.common.VMSystem;
import com.lkd.config.ConsulConfig;
import com.lkd.config.TopicConfig;
import com.lkd.contract.OrderCheck;
import com.lkd.contract.VendoutContract;
import com.lkd.contract.VendoutData;
import com.lkd.dao.OrderDao;
import com.lkd.feign.UserService;
import com.lkd.vo.*;
import com.lkd.emq.MqttProducer;
import com.lkd.entity.OrderEntity;
import com.lkd.exception.LogicException;
import com.lkd.feign.VMService;
import com.lkd.service.OrderService;
import com.lkd.utils.DistributedLock;
import com.lkd.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.BucketOrder;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl extends ServiceImpl<OrderDao, OrderEntity> implements OrderService {

    @Override
    public OrderEntity getByOrderNo(String orderNo) {
        QueryWrapper<OrderEntity> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(OrderEntity::getOrderNo,orderNo);
        return this.getOne(qw);
    }

    @Autowired
    private VMService vmService;

    @Autowired
    private UserService userService;

    @Autowired
    private MqttProducer mqttProducer;

    @Autowired
    private ConsulConfig consulConfig;

    @Autowired
    private RedisTemplate<String,String> redisTemplate;


    @Override
    public OrderEntity createOrder(PayVO payVO) {
        if(!vmService.hasCapacity(payVO.getInnerCode()
                ,Long.valueOf(payVO.getSkuId()))){
            throw new LogicException("??????????????????");
        }

        //?????????????????????????????????????????????????????????
        DistributedLock lock = new DistributedLock(
                consulConfig.getConsulRegisterHost(),
                consulConfig.getConsulRegisterPort());
        DistributedLock.LockContext lockContext = lock.getLock(payVO.getInnerCode(),60);
        if(!lockContext.isGetLock()){
            throw new LogicException("??????????????????????????????");
        }
        redisTemplate.boundValueOps(
                        VMSystem.VM_LOCK_KEY_PREF+payVO.getInnerCode())
                .set(lockContext.getSession(), Duration.ofSeconds(60));//??????redis?????????????????????

        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOpenId(payVO.getOpenId());//openId
        orderEntity.setPayStatus(VMSystem.PAY_STATUS_NOPAY);//????????????
        orderEntity.setOrderNo(payVO.getInnerCode()+System.nanoTime());//????????????

        //?????????????????????????????????????????? ,????????????????????????
        VmVO vm = vmService.getVMInfo(payVO.getInnerCode());
        BeanUtils.copyProperties( vm,orderEntity  );
        orderEntity.setAddr(vm.getNodeAddr());

        //???????????????????????????????????????????????????????????????
        SkuVO skuVo = vmService.getSku(payVO.getSkuId());
        BeanUtils.copyProperties( skuVo,orderEntity  );
        orderEntity.setAmount(skuVo.getRealPrice());//??????
        orderEntity.setStatus(VMSystem.ORDER_STATUS_CREATE);

        //?????????????????????????????????
        PartnerVO partner = userService.getPartner(vm.getOwnerId());
        BigDecimal bg = new BigDecimal(skuVo.getRealPrice());
        int bill = bg.multiply(new BigDecimal(partner.getRatio())).divide(new BigDecimal(100),0, RoundingMode.HALF_UP).intValue();
        orderEntity.setBill(bill);

        this.save(orderEntity);

        //?????????????????????????????????10?????????????????????????????????????????????????????????????????????????????????
        OrderCheck orderCheck = new OrderCheck();
        orderCheck.setOrderNo(orderEntity.getOrderNo());
        try {
            mqttProducer.send("$delayed/30/"+TopicConfig.ORDER_CHECK_TOPIC,2,orderCheck);
        } catch (Exception e) {
            log.error("send to emq error",e);
        }

        return orderEntity;
    }



    @Override
    public boolean vendout(String orderNo,Long skuId,String innerCode) {
        //????????????????????????
        var reqData = new VendoutData();
        reqData.setOrderNo(orderNo);
        reqData.setSkuId(skuId);
        //????????????
        var contract = new VendoutContract();
        contract.setVendoutData(reqData);
        contract.setInnerCode(innerCode);
        //?????????????????????  ??????????????????
        try {
            mqttProducer.send( TopicConfig.VMS_VENDOUT_TOPIC,2,contract);
        } catch (JsonProcessingException e) {
            log.error("send vendout req error.",e);
            return false;
        }
        return true;
    }


}
