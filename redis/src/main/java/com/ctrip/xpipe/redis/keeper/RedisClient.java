package com.ctrip.xpipe.redis.keeper;

import java.io.Closeable;
import java.nio.channels.FileChannel;

import io.netty.buffer.ByteBuf;

/**
 * @author wenchao.meng
 *
 * 2016年4月22日 上午11:25:07
 */
public interface RedisClient extends Infoable, Closeable{
	
	public static enum CLIENT_ROLE{
		NORMAL,
		SLAVE
	}
	
	public static enum CAPA{
		
		EOF;
		
		public static CAPA of(String capaString){
			
			if("eof".equalsIgnoreCase(capaString)){
				return EOF;
			}
			throw new IllegalArgumentException("unsupported capa type:" + capaString);
		}
	}
	
	public static enum SLAVE_STATE{
		REDIS_REPL_SEND_BULK("send_bulk"),
		REDIS_REPL_ONLINE("online");
		
		private String desc;
		SLAVE_STATE(String desc){
			this.desc = desc;
		}
		public String getDesc() {
			return desc;
		}
		
	}
	
	CLIENT_ROLE getClientRole();
	
	RedisKeeperServer getRedisKeeperServer();

	void setClientRole(CLIENT_ROLE clientState);
	
	void setSlaveListeningPort(int port);

	int getSlaveListeningPort();

	void capa(CAPA capa);
	
	void setSlaveState(SLAVE_STATE slaveState);
	
	SLAVE_STATE getSlaveState();

	void ack(Long valueOf);
	
	Long getAck();
	
	Long getAckTime();
	
	void sendMessage(ByteBuf byteBuf);
	
	void sendMessage(byte[] bytes);
	
	void beginWriteCommands(long beginOffset);
	
	void beginWriteRdb(long rdbFileSize, long rdbFileOffset);
	
	void writeFile(FileChannel fileChannel, long pos, long len);

	void rdbWriteComplete();
	
	String []readCommands(ByteBuf byteBuf);

	String info();
	
}
