package com.lkd.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.lkd.common.VMSystem;

import com.lkd.config.ConsulConfig;
import com.lkd.config.TopicConfig;
import com.lkd.contract.SupplyChannel;
import com.lkd.contract.SupplyContract;

import com.lkd.contract.VendoutContract;
import com.lkd.contract.VendoutResultContract;
import com.lkd.dao.VendingMachineDao;

import com.lkd.emq.MqttProducer;
import com.lkd.entity.*;
import com.lkd.exception.LogicException;
import com.lkd.http.vo.CreateVMReq;
import com.lkd.service.*;

import com.lkd.utils.DistributedLock;
import com.lkd.utils.UUIDUtils;
import com.lkd.vo.Pager;
import com.lkd.vo.SkuVO;
import com.lkd.vo.VmVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class VendingMachineServiceImpl extends ServiceImpl<VendingMachineDao,VendingMachineEntity> implements VendingMachineService{

    @Autowired
    private NodeService nodeService;

    @Autowired
    private ChannelService channelService;

    @Autowired
    private VmTypeService vmTypeService;

    @Override
    @Transactional(rollbackFor = {Exception.class})
    public boolean add(CreateVMReq vendingMachine) {
        VendingMachineEntity vendingMachineEntity = new VendingMachineEntity();
        vendingMachineEntity.setNodeId(Long.valueOf(vendingMachine.getNodeId()));
        vendingMachineEntity.setVmType(vendingMachine.getVmType());
        NodeEntity nodeEntity = nodeService.getById(vendingMachine.getNodeId());
        if(nodeEntity == null){
            throw new LogicException("?????????????????????");
        }
        //????????????
        BeanUtils.copyProperties(nodeEntity, vendingMachineEntity );
        vendingMachineEntity.setCreateUserId(Long.valueOf(vendingMachine.getCreateUserId()));
        vendingMachineEntity.setInnerCode(UUIDUtils.getUUID());
        vendingMachineEntity.setClientId(UUIDUtils.generateClientId( vendingMachineEntity.getInnerCode() ));
        this.save(vendingMachineEntity);
        //??????????????????
        createChannel(vendingMachineEntity);
        return true;
    }


    /**
     * ????????????
     * @param vm
     * @return
     */
    private boolean createChannel(VendingMachineEntity vm){
        VmTypeEntity vmType = vmTypeService.getById(vm.getVmType());
        List<ChannelEntity> channelList= Lists.newArrayList();
        for(int i = 1; i <= vmType.getVmRow(); i++) {
            for(int j = 1; j <= vmType.getVmCol(); j++) {
                ChannelEntity channel = new ChannelEntity();
                channel.setChannelCode(i+"-"+j);
                channel.setCurrentCapacity(0);
                channel.setInnerCode(vm.getInnerCode());
                channel.setLastSupplyTime(vm.getLastSupplyTime());
                channel.setMaxCapacity(vmType.getChannelMaxCapacity());
                channel.setVmId(vm.getId());
                channelList.add(channel);
            }
        }
        channelService.saveBatch(channelList);
        return true;
    }


    @Override
    public boolean update(Long id, Long nodeId) {
        VendingMachineEntity vm = this.getById(id);
        if(vm.getVmStatus() == VMSystem.VM_STATUS_RUNNING)
            throw new LogicException("?????????????????????");
        NodeEntity nodeEntity = nodeService.getById(nodeId);
        BeanUtils.copyProperties( nodeEntity,vm );
        return this.updateById(vm);
    }


    @Override
    public Pager<String> getAllInnerCodes(boolean isRunning, long pageIndex, long pageSize) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<VendingMachineEntity> page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageIndex,pageSize);

        QueryWrapper<VendingMachineEntity> qw = new QueryWrapper<>();
        if(isRunning){
            qw.lambda()
                    .select(VendingMachineEntity::getInnerCode)
                    .eq(VendingMachineEntity::getVmStatus,1);
        }else {
            qw.lambda()
                    .select(VendingMachineEntity::getInnerCode)
                    .ne(VendingMachineEntity::getVmStatus,1);
        }
        this.page(page,qw);
        Pager<String> result = new Pager<>();
        result.setCurrentPageRecords(page.getRecords().stream().map(VendingMachineEntity::getInnerCode).collect(Collectors.toList()));
        result.setPageIndex(page.getCurrent());
        result.setPageSize(page.getSize());
        result.setTotalCount(page.getTotal());

        return result;
    }

    @Override
    public Pager<VendingMachineEntity> query(Long pageIndex, Long pageSize, Integer status,String innerCode) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<VendingMachineEntity> page
                = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageIndex,pageSize);
        LambdaQueryWrapper<VendingMachineEntity> queryWrapper = new LambdaQueryWrapper<>();
        if(status != null){
            queryWrapper.eq(VendingMachineEntity::getVmStatus,status);
        }
        if(!Strings.isNullOrEmpty(innerCode)){
            queryWrapper.likeLeft(VendingMachineEntity::getInnerCode,innerCode);
        }
        this.page(page,queryWrapper);

        return Pager.build(page);
    }


    @Override
    public VmVO findByInnerCode(String innerCode) {
        LambdaQueryWrapper<VendingMachineEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(VendingMachineEntity::getInnerCode,innerCode);
        VendingMachineEntity vm = this.getOne(queryWrapper);
        VmVO vmVO=new VmVO();
        BeanUtils.copyProperties(vm,vmVO);
        vmVO.setNodeAddr(vm.getNode().getAddr());//??????
        vmVO.setNodeName(vm.getNode().getName());//??????
        return vmVO;
    }


    @Override
    public boolean updateStatus(String innerCode, Integer status) {
        try{
            UpdateWrapper<VendingMachineEntity> uw = new UpdateWrapper<>();
            uw.lambda()
                    .eq(VendingMachineEntity::getInnerCode,innerCode)
                    .set(VendingMachineEntity::getVmStatus,status);
            this.update(uw);

        }catch (Exception ex){
            log.error("updateStatus error,innerCode is " + innerCode + " status is " + status,ex);
            return false;
        }
        return true;
    }


    @Override
    public List<SkuVO> getSkuListByInnerCode(String innerCode) {
        //??????????????????
        List<ChannelEntity> channelList = channelService.getChannelesByInnerCode(innerCode).stream()
                .filter(c -> c.getSkuId() > 0 && c.getSku() != null).collect(Collectors.toList());
        //??????????????????????????????
        Map<SkuEntity, Integer> skuMap = channelList.stream()
                .collect(Collectors.groupingBy(
                        ChannelEntity::getSku,
                        Collectors.summingInt(ChannelEntity::getCurrentCapacity)));//??????????????????
        return skuMap.entrySet().stream().map( entry->{
                    SkuEntity sku = entry.getKey(); //????????????
                    SkuVO skuVO = new SkuVO();
                    BeanUtils.copyProperties( sku,skuVO );
                    skuVO.setImage(sku.getSkuImage());//??????
                    skuVO.setCapacity( entry.getValue() );
                    skuVO.setRealPrice(sku.getPrice());//????????????
                    return  skuVO;
                } ).sorted(Comparator.comparing(SkuVO::getCapacity).reversed())  //????????????????????????
                .collect(Collectors.toList());
    }

    @Override
    public Boolean hasCapacity(String innerCode, Long skuId) {
        var qw = new LambdaQueryWrapper<ChannelEntity>();
        qw
                .eq(ChannelEntity::getInnerCode,innerCode)
                .eq(ChannelEntity::getSkuId,skuId)
                .gt(ChannelEntity::getCurrentCapacity,0);
        return channelService.count(qw) > 0;
    }

    @Override
    @Transactional(rollbackFor = {Exception.class})
    public boolean supply(SupplyContract supply) {
        //????????????????????????
        UpdateWrapper<VendingMachineEntity> uw = new UpdateWrapper<>();
        uw.lambda()
                .eq(VendingMachineEntity::getInnerCode,supply.getInnerCode())
                .set(VendingMachineEntity::getLastSupplyTime,LocalDateTime.now());
        this.update(uw);
        //???????????????????????????????????????
        List<ChannelEntity> channelList = channelService.getChannelesByInnerCode(supply.getInnerCode());
        supply.getSupplyData()
            .forEach(
                c -> {
                    //??????stream???????????????????????????????????????????????????
                    Optional<ChannelEntity> item =
                        channelList.stream()
                            .filter(channel -> channel.getChannelCode().equals(c.getChannelId()))
                            .findFirst();
                    if (item.isPresent()) {
                        var channelEntity = item.get();
                        //??????????????????
                        channelEntity.setCurrentCapacity(channelEntity.getCurrentCapacity() + c.getCapacity());
                        //??????????????????????????????
                        channelEntity.setLastSupplyTime(LocalDateTime.now());
                        channelService.updateById(channelEntity);
                    }
                });
        return true;
    }


    @Autowired
    private MqttProducer mqttProducer;

    @Override
    public void  sendSupplyTask(String innerCode) {
        //????????????????????????????????? skuId!=0 ???
        QueryWrapper<ChannelEntity> channelQueryWrapper=new QueryWrapper<>();
        channelQueryWrapper.lambda()
                .eq(ChannelEntity::getInnerCode,innerCode )//?????????Id
                .ne(ChannelEntity::getSkuId, 0L );
        List<ChannelEntity> channelList = channelService.list(channelQueryWrapper);
        //????????????
        List<SupplyChannel> supplyChannelList = channelList.stream()
                .filter(c-> c.getCurrentCapacity()<c.getMaxCapacity()  ) //??????????????????
                .map(c -> {
                    SupplyChannel supplyChannel = new SupplyChannel();
                    supplyChannel.setChannelId(c.getChannelCode());//????????????
                    supplyChannel.setCapacity(c.getMaxCapacity() - c.getCurrentCapacity());//??????
                    supplyChannel.setSkuId( c.getSkuId());// ??????Id
                    supplyChannel.setSkuName(c.getSku().getSkuName());//????????????
                    supplyChannel.setSkuImage(c.getSku().getSkuImage()  );//????????????
                    return supplyChannel;
                }).collect(Collectors.toList());

        if(supplyChannelList.size()>0){
            //????????????????????????
            SupplyContract supplyContract=new SupplyContract();
            supplyContract.setInnerCode(innerCode);
            supplyContract.setSupplyData(supplyChannelList);
            try {
                mqttProducer.send(TopicConfig.TASK_SUPPLY_TOPIC,2,supplyContract);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }
    }

    @Autowired
    private VendoutRunningService vendoutRunningService;

    @Autowired
    private ConsulConfig consulConfig;

    @Autowired
    private RedisTemplate<String,String> redisTemplate;



    @Override
    @Transactional
    public boolean vendout(VendoutContract vendoutContract) {
        try{
            //??????????????????
            List<ChannelEntity> channelList = channelService.getChannelesByInnerCode(vendoutContract.getInnerCode());
            //????????????????????????
            Optional<ChannelEntity> channel = channelList.stream()
                    .filter(c -> c.getCurrentCapacity() > 0) //???????????????
                    .filter(c -> c.getSkuId() == vendoutContract.getVendoutData().getSkuId()) //????????????
                    .findFirst();

            if(channel.isPresent()){//??????????????????
                //??????????????????
                var channelEntity = channel.get();//??????????????????
                channelEntity.setCurrentCapacity(  channelEntity.getCurrentCapacity() - 1 );
                channelService.updateById(channelEntity);

                //????????????????????????
                var vendoutRunningEntity = new VendoutRunningEntity();
                vendoutRunningEntity.setInnerCode(vendoutContract.getInnerCode());
                vendoutRunningEntity.setOrderNo(vendoutContract.getVendoutData().getOrderNo());
                vendoutRunningEntity.setStatus(false); //????????????(??????????????????????????????????????????????????????)
                vendoutRunningEntity.setSkuId(vendoutContract.getVendoutData().getSkuId());

                vendoutRunningService.save(vendoutRunningEntity);

                //????????????????????????
                vendoutContract.getVendoutData().setChannelCode(  channelEntity.getChannelCode()  ); //????????????????????????????????????
                mqttProducer.send( TopicConfig.getVendoutTopic(vendoutContract.getInnerCode()),2,vendoutContract);

                //?????????????????????
                DistributedLock lock = new DistributedLock(
                        consulConfig.getConsulRegisterHost(),
                        consulConfig.getConsulRegisterPort());
                String sessionId = redisTemplate.boundValueOps(VMSystem.VM_LOCK_KEY_PREF + vendoutContract.getInnerCode()).get();
                lock.releaseLock(sessionId);

                return true;

            }else{
                log.info("??????,????????????");
                //todo :????????????
                return false;
            }
        }catch (Exception e){
            log.error("update vendout result error.",e);
            return false;
        }
    }

    @Override
    public boolean vendoutResult(VendoutResultContract vendoutResultContract) {

        if(!vendoutResultContract.isSuccess()){//??????????????????
            log.info(vendoutResultContract.getInnerCode()+"????????????");
            //????????????
            ChannelEntity channelInfo = channelService.getChannelInfo(
                    vendoutResultContract.getInnerCode(), vendoutResultContract.getVendoutData().getChannelCode());
            channelInfo.setCurrentCapacity( channelInfo.getCurrentCapacity()+1 ); //????????????
            channelService.updateById(channelInfo  );
            return false;
        }else{
            //??????????????????
            var qw = new LambdaQueryWrapper<VendoutRunningEntity >();
            qw.eq(VendoutRunningEntity::getOrderNo
                    ,vendoutResultContract.getVendoutData().getOrderNo());
            var runningEntity = vendoutRunningService.getOne(qw);
            if(runningEntity==null){
                return false;
            }
            runningEntity.setStatus(true);//???????????????true
            vendoutRunningService.updateById(runningEntity);
            return true;
        }

    }


}
