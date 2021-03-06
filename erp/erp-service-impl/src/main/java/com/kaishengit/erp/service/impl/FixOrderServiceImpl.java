package com.kaishengit.erp.service.impl;

import com.google.gson.Gson;
import com.kaishengit.erp.dto.FixOrderPartsVo;
import com.kaishengit.erp.dto.OrderInfoDto;
import com.kaishengit.erp.dto.OrderStateDto;
import com.kaishengit.erp.entity.*;
import com.kaishengit.erp.exception.ServiceException;
import com.kaishengit.erp.mapper.CountTimeoutMapper;
import com.kaishengit.erp.mapper.FixOrderMapper;
import com.kaishengit.erp.mapper.FixOrderPartsMapper;
import com.kaishengit.erp.quartz.CheckFixTimeOut;
import com.kaishengit.erp.service.FixOrderService;
import com.kaishengit.erp.util.Constant;
import org.joda.time.DateTime;
import org.quartz.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Session;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @author jinjianghao
 * @date 2018/8/8
 */
public class FixOrderServiceImpl implements FixOrderService {

    @Autowired
    private FixOrderMapper fixOrderMapper;

    @Autowired
    private FixOrderPartsMapper fixOrderPartsMapper;

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private SchedulerFactoryBean schedulerFactoryBean;

    @Autowired
    private CountTimeoutMapper countTimeoutMapper;

    /**
     * 将队列中的数据解析生成维修订单
     *
     * @param json
     */
    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public void createFixOrder(String json) {
        OrderInfoDto orderInfoDto = new Gson().fromJson(json, OrderInfoDto.class);

        // 解析orderInfoDto信息并入库
        // 封装维修单
        FixOrder fixOrder = new FixOrder();
        fixOrder.setOrderId(orderInfoDto.getOrder().getId());
        fixOrder.setOrderMoney(orderInfoDto.getOrder().getOrderMoney());
        fixOrder.setOrderTime(orderInfoDto.getOrder().getCreateTime());
        fixOrder.setState(orderInfoDto.getOrder().getState());
        fixOrder.setOrderType(orderInfoDto.getServiceType().getServiceName());
        fixOrder.setOrderServiceHour(orderInfoDto.getServiceType().getServiceHour());
        // 计算工时费
        fixOrder.setOrderServiceHourFee(new BigDecimal(Integer.parseInt(orderInfoDto.getServiceType().getServiceHour()) * Constant.DEFAULT_HOUR_FEE));
        // 计算配件费用
        fixOrder.setOrderPartsFee(fixOrder.getOrderMoney().subtract(fixOrder.getOrderServiceHourFee()));

        // 封装车辆信息
        fixOrder.setCarColor(orderInfoDto.getOrder().getCar().getColor());
        fixOrder.setCarLicence(orderInfoDto.getOrder().getCar().getLicenceNo());
        fixOrder.setCarType(orderInfoDto.getOrder().getCar().getCarType());

        // 封装客户信息
        fixOrder.setCustomerName(orderInfoDto.getOrder().getCustomer().getUserName());
        fixOrder.setCustomerTel(orderInfoDto.getOrder().getCustomer().getTel());

        fixOrderMapper.insert(fixOrder);

        // 配件列表入库
        for(Parts parts : orderInfoDto.getPartsList()) {
            FixOrderParts fixOrderParts = new FixOrderParts();
            fixOrderParts.setOrderId(orderInfoDto.getOrder().getId());
            fixOrderParts.setPartsId(parts.getId());
            fixOrderParts.setPartsName(parts.getPartsName());
            fixOrderParts.setPartsNo(parts.getPartsNo());
            fixOrderParts.setPartsNum(parts.getNum());

            fixOrderPartsMapper.insertSelective(fixOrderParts);
        }
    }

    /**
     * 查询待维修列表
     *
     * @return
     */
    @Override
    public List<FixOrder> findFixOrderListWithParts() {
        return fixOrderMapper.findListWithParts();
    }

    /**
     * 接收任务
     * @param id
     * @param employee
     * @throws ServiceException 还有未完成的任务，不能接收新任务
     *
     */
    @Override
    public void taskReceive(Integer id, Employee employee) throws ServiceException{

        // 判断当前员工下是否有未完成任务
        FixOrderExample fixOrderExample = new FixOrderExample();
        fixOrderExample.createCriteria().andFixEmployeeIdEqualTo(employee.getId())
                .andStateEqualTo(FixOrder.ORDER_STATE_FIXING);
        List<FixOrder> fixOrdersList = fixOrderMapper.selectByExample(fixOrderExample);

        if(fixOrdersList != null && fixOrdersList.size() > 0) {
            throw new ServiceException("还有未完成的任务，不能接收新任务");
        }

        FixOrder fixOrder = fixOrderMapper.selectByPrimaryKey(id);
        if(fixOrder == null) {
            throw new ServiceException("参数错误或该订单不存在");
        }

        fixOrder.setState(FixOrder.ORDER_STATE_FIXING);
        fixOrder.setFixEmployeeId(employee.getId());
        fixOrder.setFixEmployeeName(employee.getEmployeeName());
        fixOrderMapper.updateByPrimaryKey(fixOrder);

        OrderStateDto orderStateDto = new OrderStateDto();
        orderStateDto.setOrderId(id);
        orderStateDto.setEmployeeId(employee.getId());
        orderStateDto.setState(FixOrder.ORDER_STATE_FIXING);

        sendStateToMQ(orderStateDto);

        // 减少库存
        changePartsInventory(id, employee.getId());
        // 添加超时的定时任务
        setFixOrderTimeOutTask(id, employee.getId(), Integer.parseInt(fixOrder.getOrderServiceHour()));
    }

    /**
     * 添加超时的定时任务
     * @param orderId
     * @param employeeId
     */
    private void setFixOrderTimeOutTask(Integer orderId, Integer employeeId, Integer serviceHour) {

        Scheduler scheduler = schedulerFactoryBean.getScheduler();
        try {
            JobDetail jobDetail = JobBuilder.newJob(CheckFixTimeOut.class)
                    .withIdentity("fix:" + orderId + "-" + employeeId, "fixOrder")
                    .build();

            DateTime dateTime = new DateTime();
            dateTime = dateTime.plusHours(serviceHour);
//            dateTime = dateTime.plusMinutes(serviceHour);

            // 23 34 12 17 8 ? 2018
            String cronExpression = dateTime.getSecondOfMinute() + " " + dateTime.getMinuteOfHour() + " " + dateTime.getHourOfDay()
                    + " " + dateTime.getDayOfMonth() + " " + dateTime.getMonthOfYear() + " ? " + dateTime.getYear();

            CronScheduleBuilder scheduleBuilder = CronScheduleBuilder.cronSchedule(cronExpression);
            Trigger trigger = TriggerBuilder.newTrigger().withSchedule(scheduleBuilder).build();

            scheduler.scheduleJob(jobDetail, trigger);
            scheduler.start();
        } catch (SchedulerException e) {
            e.printStackTrace();
        }

    }

    /**
     * 发送消息到消息队列通知库存管理减少对应配件的库存
     * @param id
     */
    private void changePartsInventory(Integer id, Integer employeeId) {
        FixOrderPartsExample fixOrderExample = new FixOrderPartsExample();
        fixOrderExample.createCriteria().andOrderIdEqualTo(id);
        List<FixOrderParts> partsList = fixOrderPartsMapper.selectByExample(fixOrderExample);

        FixOrderPartsVo fixOrderPartsVo = new FixOrderPartsVo();
        fixOrderPartsVo.setEmployeeId(employeeId);
        fixOrderPartsVo.setFixOrderPartsList(partsList);


        String json = new Gson().toJson(fixOrderPartsVo);

        jmsTemplate.send("partsNum-queue", new MessageCreator() {
            @Override
            public Message createMessage(Session session) throws JMSException {
                return session.createTextMessage(json);
            }
        });
    }

    /**
     * 获得维修订单详情
     * @param id
     * @return
     */
    @Override
    public FixOrder getFixOrder(Integer id) {
        return fixOrderMapper.findWithPartsById(id);
    }

    /**
     * 维修完成
     * @param id
     */
    @Override
    public void taskDone(Integer id) {
        FixOrder fixOrder = fixOrderMapper.selectByPrimaryKey(id);
        if(fixOrder == null) {
            throw new ServiceException("参数错误或该订单不存在");
        }

        fixOrder.setState(FixOrder.ORDER_STATE_FIXED);
        fixOrderMapper.updateByPrimaryKey(fixOrder);

        OrderStateDto orderStateDto = new OrderStateDto();
        // 完成订单不需要记录员工订单关联关系，不设置即可
        orderStateDto.setOrderId(id);
        orderStateDto.setState(FixOrder.ORDER_STATE_FIXED);

        sendStateToMQ(orderStateDto);

        // 删除检测超时的定时器任务
        Scheduler scheduler = schedulerFactoryBean.getScheduler();
        try {
            scheduler.deleteJob(new JobKey("fix:" + fixOrder.getOrderId() + "-" + fixOrder.getFixEmployeeId(), "fixOrder"));
        } catch (SchedulerException e) {
            e.printStackTrace();
        }
    }

    /**
     * 添加超时数据
     * @param jobName
     */
    @Override
    public void addFixOrderTimeout(String jobName) {
        Integer orderId = Integer.valueOf(jobName.split(":")[1].split("-")[0]);
        Integer employeeId = Integer.valueOf(jobName.split(":")[1].split("-")[1]);
        // Integer serviceHour = Integer.valueOf(jobName.split(":")[1].split("-")[2]);

        CountTimeoutExample countTimeoutExample = new CountTimeoutExample();
        countTimeoutExample.createCriteria().andEmployeeIdEqualTo(employeeId)
                .andOrderIdEqualTo(orderId);

        /*List<CountTimeout> countTimeoutList = countTimeoutMapper.selectByExample(countTimeoutExample);
        // 是否已经超时过,已经超时 num++
        if(countTimeoutList != null && countTimeoutList.size() > 0) {
            CountTimeout countTimeout = countTimeoutList.get(0);
            countTimeout.setNum(countTimeout.getNum() + 1);

            countTimeoutMapper.updateByPrimaryKeySelective(countTimeout);
        } else {*/
            CountTimeout countTimeout = new CountTimeout();
            countTimeout.setEmployeeId(employeeId);
            countTimeout.setOrderId(orderId);

            countTimeoutMapper.insertSelective(countTimeout);
        /*}

        setFixOrderTimeOutTask(orderId, employeeId, serviceHour);*/
    }

    /**
     * 发送订单状态到消息队列
     * @param orderStateDto
     */
    private void sendStateToMQ(OrderStateDto orderStateDto) {
        // 将对象转成json数据传输到mq中
        String json = new Gson().toJson(orderStateDto);
        jmsTemplate.send("state-queue", new MessageCreator() {
            @Override
            public Message createMessage(Session session) throws JMSException {
                return session.createTextMessage(json);
            }
        });
    }
}
