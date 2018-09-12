package com.njusoft.its.net.client;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.njusoft.its.core.logger.LoggerManager;
import com.njusoft.its.dao.LeaveLinesDao;
import com.njusoft.its.entity.LeaveLineEntity;
import com.njusoft.its.entity.LinePointEntity;
import com.njusoft.its.entity.LocationEntity;
import com.njusoft.its.entity.StationEntity;
import com.njusoft.its.mq.LeaveLineProducer;
import com.njusoft.its.service.impl.RedisServiceImpl;
import com.njusoft.its.utils.DateUtil;
import com.njusoft.its.utils.DispatchUtils;
import com.njusoft.its.utils.IConst;
import com.njusoft.its.utils.RuntimeUtils;

@Component
public class CreateLeaveLine {
	
	private static RedisServiceImpl redisService;
	
	private static LeaveLinesDao leaveLinesDao;

	
   
	@Autowired
    public void setDatastore(RedisServiceImpl p_redisService,LeaveLinesDao p_leaveLinesDao) {
    	redisService = p_redisService;
    	leaveLinesDao = p_leaveLinesDao;
    }
	
	public static void leaveLine(LocationEntity location, String buscode){
		try{
			//0  通过当前定位的bus获取redis
			LeaveLineEntity model = (LeaveLineEntity) redisService.getBean(IConst.LEAVE_LINE+IConst.SEP+buscode, LeaveLineEntity.class);
			
			//1 获取当前定位信息的站点信息
			List<LinePointEntity> linePoints = RuntimeUtils.getInstance().getMapLineDirectionPoints()
					.get(location.getCompCode() +IConst.SEP + location.getLineCode()+IConst.SEP+location.getDirection());
			//2 获取偏离路线
			LeaveLineEntity leaveLine = fetchLeaveLinePoint(location,linePoints,buscode,model);
			
			//3 发送消息到topic,告知开始偏离
			if(null == model && leaveLine!=null && leaveLine.getEndTime()==null) {
				LeaveLineProducer.sendTopic(leaveLine,IConst.START_FLAG, IConst.LEAVELINE_TOPIC);
			}
			
			//4 偏离线路结束，发送消息到topic，告知结束偏离， (保存到数据库)删除redis对应的偏离线路
			if(leaveLine!=null && leaveLine.getEndTime()!=null) {
				
				if(null!=leaveLine){
//					leaveLinesDao.save(leaveLine);
					leaveLine.setCreateTime(new Date());
					System.out.println("消耗时间为："+(new Date().getTime()-leaveLine.getEndTime().getTime())/1000+"s");
					//发送消息到topic
					LeaveLineProducer.sendTopic(leaveLine,IConst.END_FLAG,IConst.LEAVELINE_TOPIC);
					LoggerManager.info("["+ location.getBusCode() +"]["+ DateUtil.getDateTimeFormat(location.getOccurTime()) +"]生成了离线信息,距离："+leaveLine.getDistance());
					System.out.println("生成的偏离线路为："+"开始时间："+leaveLine.getStartTime()+"startlat:"+leaveLine.getStartLat()+",startlng:"+leaveLine.getStartLng()+
							"结束时间为："+leaveLine.getEndTime()+"endlat:"+leaveLine.getEndLat()+",endlng:"+leaveLine.getEndLng());
					redisService.delete(IConst.LEAVE_LINE+IConst.SEP+buscode,LeaveLineEntity.class);
				}
				
			}else {
				LoggerManager.info("["+ location.getBusCode() +"]["+ DateUtil.getDateTimeFormat(location.getOccurTime()) +"]无离线信息");
			}
				
		}catch(Exception e){
			LoggerManager.error(e);
			e.printStackTrace();
		}
	}
	
	//创建偏离路线
	private static LeaveLineEntity fetchLeaveLinePoint(LocationEntity location,List<LinePointEntity> linePoints,String buscode, LeaveLineEntity entity) {
		if(location==null || linePoints==null || linePoints.size()<1) return null;
		double distance=0;
		List<LocationEntity> leaveLocations = new ArrayList<LocationEntity>();
		
		//1 获取当前定位点的附近点，三个点
		List<LinePointEntity>  lstPoints = fetchLinePoint(location,linePoints);
		double rFirst=0;
		double rSecond=0;
			
		//2 形成2个三角形，条件：必须满足两个三角形均有localtion定位，即localtion 0 1 和localtion 1 2
		if(lstPoints.size()>2) {
			//计算b1边长度
			double b1 = DispatchUtils.getDistance(location.getLat(),location.getLng(), lstPoints.get(0).getLat(), lstPoints.get(0).getLng());
			
			//计算c1边长度
			double c1 = DispatchUtils.getDistance(lstPoints.get(0).getLat(), lstPoints.get(0).getLng(), lstPoints.get(1).getLat(), lstPoints.get(1).getLng());
			
			//计算a边长度
			double a = DispatchUtils.getDistance(location.getLat(),location.getLng(), lstPoints.get(1).getLat(), lstPoints.get(1).getLng());
			
			//计算b边长度
			double b2 = DispatchUtils.getDistance(location.getLat(),location.getLng(), lstPoints.get(2).getLat(), lstPoints.get(2).getLng());
			
			//计算c边长度
			double c2 = DispatchUtils.getDistance( lstPoints.get(1).getLat(), lstPoints.get(1).getLng(), lstPoints.get(2).getLat(), lstPoints.get(2).getLng());
			//计算P1,P2
			double p1=(a+b1+c1)/2;
			double p2=(a+b2+c2)/2;
			//获取内切圆半径
			rFirst = getApothem( p1, a, b1, c1);
			rSecond = getApothem(p2, a, b2, c2);
		}
		//2.1  内切圆半径大于50说明当前location已偏离
		if(rFirst>50 && rSecond>50) {
			leaveLocations.add(location);
			
			if(rFirst<rSecond && rFirst>distance) {
				distance = rFirst;
			}
			if (rSecond<rFirst && rSecond>distance) {
				distance = rSecond;
			}
		}
				
		//2.3 删除 实际发车之前的数据location-realruntime
		if(null!=entity){
			long leavestart = entity.getStartTime().getTime();
			if( leavestart<location.getRealRunTime()){
				entity = null;
			}
		}
		
		//2.4 有偏离线路,if redis true 更新偏离线路实体,else 记录开始时间并生成偏离线路
		if(leaveLocations.size()>0) {
			if(null == entity){
				entity = new LeaveLineEntity();
				Date startTime = location.getOccurTime();
				entity.setStartTime(startTime);
				entity.setStartLat(String.valueOf(location.getLat()));
				entity.setStartLng(String.valueOf(location.getLng()));
				
			}
			entity.setCompCode(location.getCompCode());
			entity.setLineCode(location.getLineCode());
			entity.setLineName(location.getLineName());
			entity.setDirection(location.getDirection());
			entity.setBusCode(location.getBusCode());
			entity.setBusBrandNo(location.getBusBrandNo());
			entity.setDriverCode(location.getDriverCode());
			entity.setDriverName(location.getDriverName());
			entity.setDataProperty(String.valueOf(IConst.CREATE_AUTO_MODE));
			entity.setDistance(distance);
			StationEntity startStation = fetchLineStation(location,RuntimeUtils.getInstance().getStations());
			if(startStation!=null) {
				entity.setStartstationname(startStation.getName());
			}
			redisService.setBean(IConst.LEAVE_LINE+IConst.SEP+buscode, entity);
			return entity;
		}
		
		//2.5 无偏离线路并且redis存在，说明偏离线路已结束，记录结束时间
		if(leaveLocations.size()==0 && null!=entity) {
			Date endTime = location.getOccurTime();
			entity.setEndTime(endTime);
			entity.setEndLat(String.valueOf(location.getLat()));
			entity.setEndLng(String.valueOf(location.getLng()));
			entity.setWorkDate(location.getWorkDate());
			StationEntity endStation = fetchLineStation(location,RuntimeUtils.getInstance().getStations());
			if(endStation!=null) {
				entity.setEndstationname(endStation.getName());
			}
			Double timeSpan =new Double((entity.getEndTime().getTime()-entity.getStartTime().getTime())/1000);//偏离时长/s
			entity.setTimeSpan(timeSpan.intValue());
			if(timeSpan==0) entity = null;
			return entity;
		}
		return null;
	} 
	
	//获取内切圆半径
	private static double getApothem(double p,double a,double b,double c){
		double apothem = 0;
		if(p>0 && p>a && p>b && p>c) {
			
			double s =  Math.sqrt(p*(p-a)*(p-b)*(p-c));
			apothem = 2*s/(a+b+c);					
		}
		return apothem;
	}
	
	//获取location附近的点
	private static List<LinePointEntity> fetchLinePoint(LocationEntity location,List<LinePointEntity> linePoints){
		
		List<LinePointEntity> retVal = new ArrayList<LinePointEntity>();
		
		LinePointEntity minPoint = null;
		double minDistance = -1;
		
		for(LinePointEntity point: linePoints) {
			//获取localtion最小距离，最近点
			double curDistance = DispatchUtils.getDistance(location.getLat(),location.getLng(), point.getLat(), point.getLng());
			if(minDistance==-1 || curDistance<minDistance) {
				minDistance = curDistance;
				minPoint = point;
			}			
		}
		
		if(minPoint!=null) {
			//获取前一个点
			if(minPoint.getOrderNo()>1) {
				retVal.add(linePoints.get(minPoint.getOrderNo()-2));
			}
			//最近点
			retVal.add(minPoint);
			//后一个点，后一点确保在线路点上才能获取
			if(linePoints.size()>minPoint.getOrderNo()) {
				retVal.add(linePoints.get(minPoint.getOrderNo()));
			}
		}
		
		return retVal;
	}
	
	//获取站点信息
	private static StationEntity fetchLineStation(LocationEntity location,List<StationEntity> stations){
			
			StationEntity retVal = null;		
			double minDistance = -1;
			
			for(StationEntity entity: stations) {
				double curDistance = DispatchUtils.getDistance(location.getLat(),location.getLng(), entity.getLat(), entity.getLng());
				if(minDistance==-1 || curDistance<minDistance) {
					minDistance = curDistance;
					retVal = entity;
				}	
			}
			
			return retVal;
		}

	
		
}
