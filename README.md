# redis-springboot-config
redis在springboot中的配置，以及创建redis的接口&amp;&amp;实现类
application.properties文件、application-dev.properties文件为redis的配置文件

IRedisService.java源文件为redis的接口文件，以及RedisServiceImpl.java文件是实现类。

如果是静态类利用注解@Autowired引入文件，如果直接引用，会报nullException

private static RedisServiceImpl redisService;
 
	@Autowired
    public void setDatastore(RedisServiceImpl p_redisService) {
    	redisService = p_redisService;
    }
	
