package com.lkd.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.common.base.Strings;
import com.lkd.common.VMSystem;
import com.lkd.dao.UserDao;
import com.lkd.entity.UserEntity;
import com.lkd.http.view.TokenObject;
import com.lkd.http.vo.LoginReq;
import com.lkd.http.vo.LoginResp;
import com.lkd.service.PartnerService;
import com.lkd.service.UserService;
import com.lkd.sms.SmsSender;
import com.lkd.utils.BCrypt;
import com.lkd.utils.JWTUtil;
import com.lkd.vo.Pager;
import com.lkd.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserDao,UserEntity> implements UserService{
    @Autowired
    private RedisTemplate<String,String> redisTemplate;
    @Autowired
    private PartnerService partnerService;

    @Autowired
    private SmsSender smsSender;
    @Override
    public Integer getOperatorCount() {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserEntity::getRoleCode,"1002");

        return this.count(wrapper);
    }

    @Override
    public Integer getRepairerCount() {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserEntity::getRoleCode,"1003");

        return this.count(wrapper);
    }

    @Override
    public Pager<UserEntity> findPage(long pageIndex, long pageSize, String userName,Integer roleId,Boolean isRepair) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserEntity> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageIndex,pageSize);

        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        if(!Strings.isNullOrEmpty(userName)){
            wrapper.like(UserEntity::getUserName,userName);
        }
        if(roleId != null && roleId > 0){
            wrapper.eq(UserEntity::getRoleId,roleId);
        }
        if(isRepair != null && isRepair == true){
            wrapper.eq(UserEntity::getRoleCode,"1003");
        }
        if(isRepair != null && isRepair == false){
            wrapper.eq(UserEntity::getRoleCode,"1002");
        }
        wrapper.ne(UserEntity::getRoleId,1);
        this.page(page,wrapper);
        page.getRecords().forEach(u->{
            u.setPassword("");
            u.setSecret("");
        });

        return Pager.build(page);
    }

    @Override
    public LoginResp login(LoginReq req) throws IOException {
        if(req.getLoginType() == VMSystem.LOGIN_ADMIN){
            return this.adminLogin(req);
        }else if(req.getLoginType() == VMSystem.LOGIN_EMP){
            return this.empLogin(req);
        }else if(req.getLoginType() == VMSystem.LOGIN_PARTNER){
            return partnerService.login(req);
        }
        LoginResp resp = new LoginResp();
        resp.setSuccess(false);
        resp.setMsg("??????????????????");

        return resp;
    }



    @Override
    public void sendCode(String mobile){
        //????????????
        if(Strings.isNullOrEmpty(mobile)) return;

        //??????????????????????????????????????????
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper
                .eq(UserEntity::getMobile,mobile);
        if(this.count(wrapper)<=0) return;  //??????????????????????????????
        if(redisTemplate.opsForValue().get(mobile) != null) return;  //??????5?????????????????????
        //??????5??????????????????
        StringBuilder sbCode = new StringBuilder();
        Stream
                .generate(()-> new Random().nextInt(10))
                .limit(5)
                .forEach(x-> sbCode.append(x));
        //??????????????????redis  ???5????????????
        redisTemplate.opsForValue().set(mobile,sbCode.toString(), Duration.ofMinutes(5));
        log.info("??????????????????"+sbCode.toString());
        //????????????
        smsSender.sendMsg(mobile,sbCode.toString());
    }

    @Override
    public List<UserVO> getOperatorList(Long regionId) {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper
                .eq(UserEntity::getRoleCode,"1002")
                .eq(UserEntity::getRegionId,regionId)
                .eq(UserEntity::getStatus,true);

        return this.list(wrapper)
                .stream()
                .map(u->{
                    UserVO vo = new UserVO();
                    BeanUtils.copyProperties(u,vo);
                    vo.setRoleName(u.getRole().getRoleName());
                    vo.setRoleCode(u.getRoleCode());
                    vo.setUserId(u.getId());
                    return vo;
                }).collect(Collectors.toList());
    }

    @Override
    public List<UserVO> getRepairerList(Long regionId) {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper
                .eq(UserEntity::getRoleCode,"1003")
                .eq(UserEntity::getRegionId,regionId)
                .eq(UserEntity::getStatus,true);

        return this.list(wrapper)
                .stream()
                .map(u->{
                    UserVO vo = new UserVO();
                    BeanUtils.copyProperties(u,vo);
                    vo.setRoleName(u.getRole().getRoleName());
                    vo.setRoleCode(u.getRoleCode());
                    vo.setUserId(u.getId());
                    return vo;
                }).collect(Collectors.toList());
    }

    @Override
    public Integer getCountByRegion(Long regionId, Boolean isRepair) {
        var qw = new LambdaQueryWrapper<UserEntity>();
        qw.eq(UserEntity::getRegionId,regionId);
        if(isRepair){
            qw.eq(UserEntity::getRoleId,3);
        }else {
            qw.eq(UserEntity::getRoleId,2);
        }

        return this.count(qw);
    }


    /**
     * ???????????????
     * @param req
     * @return
     * @throws IOException
     */
    private LoginResp adminLogin(LoginReq req) throws IOException {
        LoginResp resp = new LoginResp();
        resp.setSuccess(false);
        String code =redisTemplate.boundValueOps(req.getClientToken()).get();
        if(Strings.isNullOrEmpty(code)){
            resp.setMsg("???????????????");
            return resp;
        }
        if(!req.getCode().equals(code)){
            resp.setMsg("???????????????");
            return resp;
        }
        QueryWrapper<UserEntity> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(UserEntity::getLoginName,req.getLoginName());
        UserEntity userEntity = this.getOne(qw);
        if(userEntity == null){
            resp.setMsg("????????????????????????");
            return resp;
        }
        boolean loginSuccess = BCrypt.checkpw(req.getPassword(),userEntity.getPassword());
        if(!loginSuccess){
            resp.setMsg("????????????????????????");
            return resp;
        }
        return okResp(userEntity,VMSystem.LOGIN_ADMIN);
    }

    /**
     * ??????????????????token
     * @param userEntity
     * @param loginType
     * @return
     */
    private LoginResp okResp(UserEntity userEntity,Integer loginType ) throws IOException {
        LoginResp resp = new LoginResp();
        resp.setSuccess(true);
        resp.setRoleCode(userEntity.getRoleCode());
        resp.setUserName(userEntity.getUserName());
        resp.setUserId(userEntity.getId());
        resp.setRegionId(userEntity.getRegionId()+"");
        resp.setMsg("????????????");

        TokenObject tokenObject = new TokenObject();
        tokenObject.setUserId(userEntity.getId());
        tokenObject.setMobile(userEntity.getMobile());
        tokenObject.setLoginType(loginType);
        String token = JWTUtil.createJWTByObj(tokenObject,userEntity.getMobile() + VMSystem.JWT_SECRET);
        resp.setToken(token);
        return resp;
    }


    /**
     * ????????????????????????
     * @param req
     * @return
     * @throws IOException
     */
    private LoginResp empLogin(LoginReq req) throws IOException {
        LoginResp resp = new LoginResp();
        resp.setSuccess(false);
        String code =redisTemplate.boundValueOps(req.getMobile()).get();
        if(Strings.isNullOrEmpty(code)){
            resp.setMsg("???????????????");
            return resp;
        }
        if(!req.getCode().equals(code)){
            resp.setMsg("???????????????");
            return resp;
        }

        QueryWrapper<UserEntity> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(UserEntity::getMobile, req.getMobile());
        UserEntity userEntity = this.getOne(qw);
        if (userEntity == null){
            resp.setMsg("??????????????????");
            return resp;
        }
        return okResp( userEntity,VMSystem.LOGIN_EMP );
    }



}
