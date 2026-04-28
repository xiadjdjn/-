package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.OrderService;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.websocket.WebSocketServer;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WebSocketServer webSocketServer;

    /**
     * 订单提交
     * @param ordersSubmitDTO
     * @return
     */
    @Transactional
    @Override
    public OrderSubmitVO orderSubmit(OrdersSubmitDTO ordersSubmitDTO) {
        //处理业务异常(地址为空、购物车数据为空)
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if(addressBook==null){
            //抛出异常
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }
        //查询当前用户的购物车数据
        Long userId = BaseContext.getCurrentId();

        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);
        List<ShoppingCart> shoppingCartlist = shoppingCartMapper.list(shoppingCart);

        if(shoppingCartlist==null&&shoppingCartlist.size()==0){
            //抛出异常
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);

        }

        //向订单表插入数据
        Orders orders = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO,orders);
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setPhone(addressBook.getPhone());
        orders.setAddress(addressBook.getDetail());
        orders.setConsignee(addressBook.getConsignee());
        orders.setUserId(userId);

        orderMapper.insert(orders);

        //向订单明细表插入n条数据
        List<OrderDetail> orderDetailList = new ArrayList<>();
        for (ShoppingCart cart : shoppingCartlist) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart,orderDetail);
            orderDetail.setOrderId(orders.getId());
            orderDetailList.add(orderDetail);
        }
        orderDetailMapper.insertBatch(orderDetailList);

        //清空当前用户购物车数据
        ShoppingCart Cart = new ShoppingCart();
        Cart.setUserId(userId);
        shoppingCartMapper.deleteShoppingCart(Cart);

        //封装结果返回
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder().id(orders.getId())
            .orderTime(orders.getOrderTime())
            .orderNumber(orders.getNumber())
            .orderAmount(orders.getAmount())
            .build();

        return orderSubmitVO;
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

        // TODO 关闭微信支付接口防止报错
        //调用微信支付接口，生成预支付交易单
        /*JSONObject jsonObject = weChatPayUtil.pay(
            ordersPaymentDTO.getOrderNumber(), //商户订单号
            new BigDecimal(0.01), //支付金额，单位 元
            "苍穹外卖订单", //商品描述
            user.getOpenid() //微信用户的openid
        );

        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
            throw new OrderBusinessException("该订单已支付");
        }*/

        JSONObject jsonObject = new JSONObject();//模拟
        jsonObject.put("code", "ORERPAID");//模拟

        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));

        //替代支付成功后的数据库订单状态修改,多定义一个方法进行修改
        Integer OrderPaidStatus = Orders.PAID;//支付状态:已支付
        Integer OrderStatus = Orders.TO_BE_CONFIRMED;//订单状态:待确认

        //更新支付时间
        LocalDateTime check_out_time = LocalDateTime.now();

        //获取订单号码
        String orderNumber = ordersPaymentDTO.getOrderNumber();

        //调用updateStatus替换支付更新数据库
        orderMapper.updateStatus(orderNumber,OrderPaidStatus,OrderStatus,check_out_time);


        // 根据订单号查询订单
        Orders ordersDB = orderMapper.getByNumber(ordersPaymentDTO.getOrderNumber());
        //通过websocket向客户端浏览器推送消息
        HashMap map = new HashMap<>();
        map.put("type",1);//1 表示来单消息,2 表示催单消息
        map.put("orderId",ordersDB.getId());
        map.put("content","订单号: "+ordersDB.getNumber());
        String json = JSON.toJSONString(map);
        webSocketServer.sendToAllClient(json);


        return vo;
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {

        // 根据订单号查询订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
            .id(ordersDB.getId())
            .status(Orders.TO_BE_CONFIRMED)
            .payStatus(Orders.PAID)
            .checkoutTime(LocalDateTime.now())
            .build();

        orderMapper.update(orders);
    }

    /**
     * 查询历史订单
     * @param ordersPageQueryDTO
     * @return
     */
    @Override
    public PageResult historyOrders(OrdersPageQueryDTO ordersPageQueryDTO) {
        //1.设置分页参数(PageHelper)
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());

        //2.查询订单信息
        //设置用户id
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());

        Page<OrderVO> page =orderMapper.page(ordersPageQueryDTO);
        if(page.getResult()!= null&& page.getResult().size()>0){
            for(OrderVO orders:page.getResult()){
                List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(orders.getId());
                orders.setOrderDetailList(orderDetails);
            }
        }
        return new PageResult(page.getTotal(),page.getResult());
    }

    /**
     * 查询订单详情
     * @param id
     * @return
     */
    @Override
    public OrderVO details(Long id) {
        //根据订单id查询订单信息
        Orders orders = orderMapper.getById(id);

        //根据订单id查询订单明细
        List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(id);

        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders,orderVO);
        orderVO.setOrderDetailList(orderDetails);
        return orderVO;
    }

    /**
     * 用户取消订单
     * @param id
     */
    @Override
    public void userCancelById(Long id) throws Exception {
        //根据id查询订单
        Orders orderDB = orderMapper.getById(id);

        //校验订单是否存在
        if(orderDB == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        //校验订单状态
        if(orderDB.getStatus()>2){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(orderDB.getId());

        //订单处于待接单状态下取消,需要退款
        if(orderDB.getStatus().equals(Orders.TO_BE_CONFIRMED)){
            //调用微信支付退款接口
            //TODO 关闭退款接口防止报错
            /*weChatPayUtil.refund(
                orderDB.getNumber(), //商户订单号
                orderDB.getNumber(), //商户退款单号
                new BigDecimal(0.01), //退款金额，单位 元
                new BigDecimal(0.01));//订单金额，单位 元*/

            //支付状态修改为 退款
            orders.setPayStatus(Orders.REFUND);
        }

        //更新订单状态、取消原因、取消时间
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("用户取消");
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);


    }

    /**
     * 再来一单
     * @param id
     */
    @Override
    public void repetition(Long id) {
        //查询当前用户id
        Long userId = BaseContext.getCurrentId();

        //根据订单id查询订单详细数据
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        //将订单数据转换为购物车数据
        List<ShoppingCart>shoppingCarts = orderDetailList.stream().map(x->{
            ShoppingCart shoppingCart = new ShoppingCart();

            //将原有订单详细数据复制到购物车数据中
            BeanUtils.copyProperties(x,shoppingCart,"id");
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());
            return shoppingCart;
            }
        ).collect(Collectors.toList());

        //将购物车对象批量插入数据库
        shoppingCartMapper.insertBatch(shoppingCarts);
    }

    /**
     * 后台订单搜索
     * @param ordersPageQueryDTO
     * @return
     */
    @Override
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        //1.设置分页参数(PageHelper)
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());

        //查询订单信息
        Page<OrderVO> page = orderMapper.page(ordersPageQueryDTO);

        if(page.getResult()!= null && page.getResult().size()>0){
            for(OrderVO orders:page.getResult()){
                //查询订单菜品详情数据
                List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(orders.getId());

                //将每条菜品信息拼接为字符串(格式:宫保鸡丁*3)
                List<String> orderDishList = orderDetails.stream().map(x->{
                    String orderDish = x.getName()+"*"+x.getNumber()+";";
                    return orderDish;
                }).collect(Collectors.toList());

                //将该订单对应所有菜品信息拼接到一起
                String orderDishString = String.join("", orderDishList);

                orders.setOrderDishes(orderDishString);
            }

        }

        return new PageResult(page.getTotal(),page.getResult());
    }

    /**
     * 各个状态订单数量
     * @return
     */
    @Override
    public OrderStatisticsVO statistics() {
        //根据状态,分别查询出待接单、待派送、派送中的订单数量
        Integer toBeConfirmed = orderMapper.countStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmed = orderMapper.countStatus(Orders.CONFIRMED);
        Integer deliveryInProgress = orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS);

        //将查询出的数据封装成OrderStatisticsVO对象并返回
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        return orderStatisticsVO;
    }

    /**
     * 订单接单
     */
    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        //构建订单对象,修改数据
        Orders orders = Orders.builder()
            .id(ordersConfirmDTO.getId())
            .status(Orders.CONFIRMED)
            .build();
        orderMapper.update(orders);
    }

    /**
     * 订单拒单
     * @param ordersRejectionDTO
     */
    @Override
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception {
        //根据id查询订单
        Orders orderDB = orderMapper.getById(ordersRejectionDTO.getId());

        //当订单存在且状态为2时才执行拒单操作
        if(orderDB == null || !orderDB.getStatus().equals(Orders.TO_BE_CONFIRMED)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        //支付状态
        Integer payStatus = orderDB.getPayStatus();
        if(payStatus.equals(Orders.PAID)){
            //TODO 关闭退款接口防止报错
            //用户已支付,需要退款
            /*String refund = weChatPayUtil.refund(
                orderDB.getNumber(),
                orderDB.getNumber(),
                new BigDecimal(0.01),
                new BigDecimal(0.01));*/
        }

        //退款后需要根据订单id更新订单状态、拒单原因、取消时间
        Orders orders = new Orders();
        orders.setId(orderDB.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setRejectionReason(ordersRejectionDTO.getRejectionReason());
        orders.setCancelTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    /**
     * 商家取消订单
     * @param ordersCancelDTO
     */
    @Override
    public void cancel(OrdersCancelDTO ordersCancelDTO) throws Exception {
        //根据id查询订单
        Orders ordersDB = orderMapper.getById(ordersCancelDTO.getId());

        //校验订单是否存在
        if(ordersDB == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        //查询支付状态
        Integer payStatus = ordersDB.getPayStatus();
        if(payStatus.equals(Orders.PAID)){
            //TODO 关闭退款接口防止报错
            //订单已支付,需要退款
            /*String refund = weChatPayUtil.refund(
                ordersDB.getNumber(),
                ordersDB.getNumber(),
                new BigDecimal(0.01),
                new BigDecimal(0.01));*/
        }

        //取消订单退款后,根据id更新订单状态、取消原因、取消时间
        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason(ordersCancelDTO.getCancelReason());
        orders.setCancelTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    /**
     * 订单派送
     * @param id
     */
    @Override
    public void delivery(Long id) {
        //根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        //校验订单是否存在,并且状态为3
        if(ordersDB==null||ordersDB.getStatus().equals(Orders.CONFIRMED)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = Orders.builder()
            .id(id)
            .status(Orders.DELIVERY_IN_PROGRESS)//修改订单为派送中
            .build();
        orderMapper.update(orders);
    }


    /**
     * 订单完成
     * @param id
     */
    @Override
    public void complete(Long id) {
        //根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        //校验订单是否存在,并且状态为4
        if(ordersDB==null||!ordersDB.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(id);
        orders.setStatus(Orders.COMPLETED);//状态为完成
        orders.setDeliveryTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    /**
     * 客户催单
     * @param id
     */
    @Override
    public void reminder(Long id) {
        //根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        //校验订单是否存在
        if(ordersDB==null){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Map map = new HashMap<>();
        map.put("type",2);//1 表示来单消息,2 表示催单消息
        map.put("orderId",id);
        map.put("content","订单号: "+ordersDB.getNumber());

        //通过websocket向客户端浏览器推送消息
        webSocketServer.sendToAllClient(JSON.toJSONString(map));
    }
}

