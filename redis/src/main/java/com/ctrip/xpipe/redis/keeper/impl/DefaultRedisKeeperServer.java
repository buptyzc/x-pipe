package com.ctrip.xpipe.redis.keeper.impl;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.ctrip.xpipe.api.endpoint.Endpoint;
import com.ctrip.xpipe.exception.XpipeException;
import com.ctrip.xpipe.netty.NettySimpleMessageHandler;
import com.ctrip.xpipe.redis.keeper.CommandsListener;
import com.ctrip.xpipe.redis.keeper.RdbFileListener;
import com.ctrip.xpipe.redis.keeper.RedisClient;
import com.ctrip.xpipe.redis.keeper.RedisClient.CLIENT_ROLE;
import com.ctrip.xpipe.redis.keeper.RedisKeeperServer;
import com.ctrip.xpipe.redis.keeper.ReplicationStore;
import com.ctrip.xpipe.redis.keeper.handler.CommandHandlerManager;
import com.ctrip.xpipe.redis.keeper.handler.SlaveOfCommandHandler.SlavePromotionInfo;
import com.ctrip.xpipe.redis.keeper.netty.NettyMasterHandler;
import com.ctrip.xpipe.redis.keeper.netty.NettySlaveHandler;
import com.ctrip.xpipe.redis.protocal.Command;
import com.ctrip.xpipe.redis.protocal.CommandRequester;
import com.ctrip.xpipe.redis.protocal.RedisProtocol;
import com.ctrip.xpipe.redis.protocal.cmd.DefaultCommandRequester;
import com.ctrip.xpipe.redis.protocal.cmd.Psync;
import com.ctrip.xpipe.redis.protocal.cmd.Replconf;
import com.ctrip.xpipe.redis.protocal.cmd.Replconf.ReplConfType;
import com.ctrip.xpipe.thread.NamedThreadFactory;
import com.ctrip.xpipe.utils.OsUtils;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * @author wenchao.meng
 *
 * 2016年3月24日 下午2:08:26
 */
public class DefaultRedisKeeperServer extends AbstractRedisServer implements RedisKeeperServer{
	
	public static final int REPLCONF_INTERVAL_MILLI = 1000;
	
	private int masterConnectRetryDelaySeconds = 5;
	
	private KEEPER_STATE keeperState = KEEPER_STATE.NORMAL;

	private Endpoint masterEndpoint;
	private String keeperRunid;
	
	private long keeperStartTime;
	
	private ReplicationStore replicationStore;

	private Channel masterChannel;
	private EventLoopGroup slaveEventLoopGroup = new NioEventLoopGroup();;

	private int retry = 3;
	private int keeperPort;

    private EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private EventLoopGroup workerGroup = new NioEventLoopGroup();

	private Map<Channel, RedisClient>  redisClients = new ConcurrentHashMap<Channel, RedisClient>(); 
	
	private ScheduledExecutorService scheduled;
	
	private CommandRequester commandRequester;
	
	public DefaultRedisKeeperServer(Endpoint masterEndpoint, ReplicationStore replicationStore, String keeperRunid, int keeperPort) {
		this(masterEndpoint, replicationStore, keeperRunid, keeperPort, null, null, 3);
	}
	
	public DefaultRedisKeeperServer(Endpoint masterEndpoint, ReplicationStore replicationStore, String keeperRunid, int keeperPort, ScheduledExecutorService scheduled, CommandRequester commandRequester, int retry) {

		this.masterEndpoint = masterEndpoint;
		this.replicationStore = replicationStore;
		this.replicationStore.setMasterAddress(masterEndpoint);
		this.keeperRunid = keeperRunid;
		this.retry = retry;
		this.keeperPort = keeperPort;
		this.scheduled = scheduled;
		if(scheduled == null){
			this.scheduled = Executors.newScheduledThreadPool(OsUtils.getCpuCount(), new NamedThreadFactory(masterEndpoint.toString()));
		}
		this.commandRequester = commandRequester;
		if(commandRequester == null){
			this.commandRequester = new DefaultCommandRequester(this.scheduled);
		}
	}

	
	@Override
	protected void doStart() throws Exception {
		super.doStart();
		keeperStartTime = System.currentTimeMillis();
		
		startServer();
		connectMaster();
		
	}
	
	@Override
	protected void doStop() throws Exception {
		
		stopServer();
		disConnectWithMaster();
		super.doStop();
	}
	

	private void stopServer() {
		
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
		
	}

	protected void startServer() throws InterruptedException {
		
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
         .channel(NioServerSocketChannel.class)
         .handler(new LoggingHandler(LogLevel.INFO))
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override
             public void initChannel(SocketChannel ch) throws Exception {
                 ChannelPipeline p = ch.pipeline();
                 p.addLast(new LoggingHandler(LogLevel.DEBUG));
                 p.addLast(new NettySimpleMessageHandler());
                 p.addLast(new NettyMasterHandler(DefaultRedisKeeperServer.this, new CommandHandlerManager()));
             }
         });
        b.bind(keeperPort).sync();
    }
		
	private void disConnectWithMaster() {
		if(masterChannel != null){
			masterChannel.close();
		}
	}
		
	private void connectMaster() {
		
		if(!shouldConnectMaster()){
			return;
		}

        Bootstrap b = new Bootstrap();
        b.group(slaveEventLoopGroup)
         .channel(NioSocketChannel.class)
         .option(ChannelOption.TCP_NODELAY, true)
         .handler(new ChannelInitializer<SocketChannel>() {
             @Override
             public void initChannel(SocketChannel ch) throws Exception {
                 ChannelPipeline p = ch.pipeline();
                 p.addLast(new LoggingHandler(LogLevel.DEBUG));
                 p.addLast(new NettySimpleMessageHandler());
                 p.addLast(new NettySlaveHandler(DefaultRedisKeeperServer.this, commandRequester));
             }
         });

        int i = 0;
		for(; i < retry ; i++){
			try{
				ChannelFuture f = b.connect(masterEndpoint.getHost(), masterEndpoint.getPort());
		        f.sync();
		        break;
			}catch(Throwable th){
				logger.error("[connectMaster][fail]" + masterEndpoint, th);
			}
		}
		
		if(i == retry){
			
			if(logger.isInfoEnabled()){
				logger.info("[connectMaster][fail, retry after "  + masterConnectRetryDelaySeconds + " seconds]" + masterEndpoint);
			}
			scheduled.schedule(new Runnable() {
				@Override
				public void run() {
					connectMaster();
				}
			}, masterConnectRetryDelaySeconds, TimeUnit.SECONDS);
		}
	}

	private void scheduleReplconf() {
		
		if(logger.isInfoEnabled()){
			logger.info("[scheduleReplconf]" + this);
		}
		
		scheduled.scheduleWithFixedDelay(new Runnable() {
			
			@Override
			public void run() {
				try{
					Command command = new Replconf(ReplConfType.ACK, String.valueOf(replicationStore.endOffset()));
					command.request(masterChannel);
				}catch(Throwable th){
					logger.error("[run][send replack error]" + DefaultRedisKeeperServer.this, th);
				}
			}
		}, REPLCONF_INTERVAL_MILLI, REPLCONF_INTERVAL_MILLI, TimeUnit.MILLISECONDS);
	}

	private Command psyncCommand(){
		
		Psync psync = null;
		if(replicationStore.getMasterRunid() == null){
			psync = new Psync(replicationStore);
		}else{
			psync = new Psync(replicationStore.getMasterRunid(), replicationStore.endOffset() + 1, replicationStore);
		}
		psync.addPsyncObserver(this);
		return psync;
	}

	private Command listeningPortCommand(){
		
		return new Replconf(ReplConfType.LISTENING_PORT, String.valueOf(keeperPort));
	}


	@Override
	public void beginWriteRdb() {
		
	}

	@Override
	public void endWriteRdb() {
		scheduleReplconf();
	}

	@Override
	public void masterConnected(Channel channel) {
		
		try {
			setKeeperServerState(KEEPER_STATE.NORMAL);
			this.masterChannel = channel;
			commandRequester.request(channel, listeningPortCommand());
			commandRequester.request(channel, psyncCommand());
			
		} catch (XpipeException e) {
			logger.error("[slaveConnected]" + channel, e);
		}
	}

	@Override
	public void masterDisconntected(Channel channel) {
		connectMaster();
	}


	@Override
	public RedisClient clientConnected(Channel channel) {
		
		RedisClient redisClient = new DefaultRedisClient(channel, this);
		redisClients.put(channel, redisClient);
		return redisClient;
	}

	@Override
	public void clientDisConnected(Channel channel) {
		
		redisClients.remove(channel);
	}

	@Override
	public String toString() {
		return "master:" + this.masterEndpoint + ",masterRunId:" + replicationStore.getMasterRunid() + ",offset:" + replicationStore.endOffset();
	}

	@Override
	public String getKeeperRunid() {
		
		return this.keeperRunid;
	}

	@Override
	public long getBeginReploffset() {
		return replicationStore.beginOffset();
	}

	@Override
	public long getEndReploffset() {
		return replicationStore.endOffset();
	}

	@Override
	public void addCommandsListener(Long offset, CommandsListener commandsListener) {
		try {
			replicationStore.addCommandsListener(offset, commandsListener);
		} catch (IOException e) {
			logger.error("[addCommandsListener]" + offset +"," + commandsListener, e);
		}
	}

	@Override
	public void readRdbFile(RdbFileListener rdbFileListener) throws IOException {
		replicationStore.readRdbFile(rdbFileListener);
	}
	
	@Override
	public Set<RedisClient> allClients() {
		return new HashSet<>(redisClients.values());
	}

	@Override
	public SERVER_ROLE role() {
		return SERVER_ROLE.KEEPER;
	}

	@Override
	public String info() {
		
		String info = "os:" + OsUtils.osInfo() + RedisProtocol.CRLF;
		info += "run_id:" + keeperRunid + RedisProtocol.CRLF;
		info += "uptime_in_seconds:" + (System.currentTimeMillis() - keeperStartTime)/1000;
		return info;
	}

	@Override
	public Map<Channel, RedisClient> slaves() {

		Map<Channel, RedisClient> slaves = new HashMap<>();

		for (Entry<Channel, RedisClient> entry : redisClients.entrySet()) {

			if (entry.getValue().getClientRole() == CLIENT_ROLE.SLAVE) {
				slaves.put(entry.getKey(), entry.getValue());
			}
		}
		return slaves;
	}

	@Override
	public CommandRequester getCommandRequester() {
		return commandRequester;
	}
	
   public ReplicationStore getReplicationStore() {
	   return replicationStore;
   }

   
   
	@Override
	public void setKeeperServerState(KEEPER_STATE keeperState, Object info) {
		
		@SuppressWarnings("unused")
		KEEPER_STATE oldState = this.keeperState;
		this.keeperState = keeperState;
		
		logger.info("[setKeeperServerState]{},{}" ,keeperState, info);

		switch(keeperState){
			case NORMAL:
				break;
			case BEGIN_PROMOTE_SLAVE:
				disConnectWithMaster();
				break;
			case COMMANDS_SEND_FINISH:
				break;
			case SLAVE_PROMTED:
				SlavePromotionInfo promotionInfo = (SlavePromotionInfo) info;
				masterChanged(promotionInfo.getKeeperOffset(), promotionInfo.getNewMasterEndpoint()
						, promotionInfo.getNewMasterRunid(), promotionInfo.getNewMasterReplOffset());
				connectMaster();
				break;
			default:
				throw new IllegalStateException("unkonow state:" + keeperState);
		}
		
	}
	
	@Override
	public void setKeeperServerState(KEEPER_STATE keeperState) {
		this.setKeeperServerState(keeperState, null);
	}

	private boolean shouldConnectMaster() {
		
		if(keeperState == KEEPER_STATE.BEGIN_PROMOTE_SLAVE || keeperState == KEEPER_STATE.COMMANDS_SEND_FINISH){
			return false;
		}
		
		return true;
	}

	public void masterChanged(long keeperOffset, Endpoint newMasterEndpoint, String newMasterRunid, long newMasterReplOffset) {
		
		this.masterEndpoint = newMasterEndpoint;
		replicationStore.masterChanged(newMasterEndpoint, newMasterRunid, newMasterReplOffset - keeperOffset);
	}
}
