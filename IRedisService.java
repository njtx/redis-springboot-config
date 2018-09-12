package com.njusoft.its.service;

import java.util.List;

/**
 * Created by Administrator on 2017/3/1 14:57.
 */
public interface IRedisService<T> {
	public boolean set(String key, String value);
	
	public String get(String key);
	
	public boolean expire(String key,long expire);
	
	public <T> boolean setList(String key ,List<T> list);
	
	public <T> List<T> getList(String key,Class<T> clz);
	
	public long lpush(String key,Object obj);
	
	public long rpush(String key,Object obj);
	
	public String lpop(String key);
	
	public boolean delete(String key,Class<T> clz);
	
	public <T> boolean setBean(String key ,T clz);
	
	public T getBean(String key,Class<T> clz);
	
}