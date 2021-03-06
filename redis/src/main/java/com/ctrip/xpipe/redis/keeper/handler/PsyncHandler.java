package com.ctrip.xpipe.redis.keeper.handler;


import java.io.IOException;

import com.ctrip.xpipe.redis.keeper.RedisClient;
import com.ctrip.xpipe.redis.keeper.RedisKeeperServer;
import com.ctrip.xpipe.redis.protocal.cmd.Psync;
import com.ctrip.xpipe.redis.protocal.protocal.SimpleStringParser;
import com.ctrip.xpipe.redis.keeper.RedisClient.CLIENT_ROLE;
import com.ctrip.xpipe.redis.keeper.store.DefaultRdbFileListener;

/**
 * @author wenchao.meng
 *
 * 2016年4月22日 上午11:49:02
 */
public class PsyncHandler extends AbstractCommandHandler{
	
	@Override
	protected void doHandle(String[] args, RedisClient redisClient) {
		
		RedisKeeperServer redisKeeperServer = redisClient.getRedisKeeperServer();
		
		redisClient.setClientRole(CLIENT_ROLE.SLAVE);
		
		if(args[0].equals("?")){
			doFullSync(redisClient);
		}else if(args[0].equals(redisKeeperServer.getKeeperRunid())){
			
			Long beginOffset = redisKeeperServer.getBeginReploffset();
			Long endOffset = redisKeeperServer.getEndReploffset();
			Long offset = Long.valueOf(args[1]);
			
			if((offset > (endOffset + 1)) || (offset < beginOffset)){
				if(logger.isInfoEnabled()){
					logger.info("[doHandle][offset out of range, do FullSync]" + beginOffset + "," + endOffset + "," + offset);
				}
				doFullSync(redisClient);
			}else{
				doPartialSync(redisClient, offset);
			}
		}else{
			doFullSync(redisClient);
		}
	}

	private void doPartialSync(RedisClient redisClient, Long offset) {
		
		if(logger.isInfoEnabled()){
			logger.info("[doPartialSync]" + redisClient);
		}
		
		SimpleStringParser simpleStringParser = new SimpleStringParser(Psync.PARTIAL_SYNC);
		redisClient.sendMessage(simpleStringParser.format());
		redisClient.beginWriteCommands(offset);
	}

	private void doFullSync(RedisClient redisClient) {

		try {
			if(logger.isInfoEnabled()){
				logger.info("[doFullSync]" + redisClient);
			}
			RedisKeeperServer redisKeeperServer = redisClient.getRedisKeeperServer();
			redisKeeperServer.readRdbFile(new DefaultRdbFileListener(redisClient));
		} catch (IOException e) {
			logger.error("[doFullSync][close client]" + redisClient, e);
			try {
				redisClient.close();
			} catch (IOException e1) {
				logger.error("[doFullSync]" + redisClient, e1);
			}
		}
	}

	@Override
	public String[] getCommands() {
		
		return new String[]{"psync", "sync"};
	}
}
