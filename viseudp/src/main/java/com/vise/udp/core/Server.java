package com.vise.udp.core;

import com.vise.log.ViseLog;
import com.vise.udp.command.KeepAlive;
import com.vise.udp.common.UdpConstant;
import com.vise.udp.config.UdpConfig;
import com.vise.udp.core.inter.IData;
import com.vise.udp.core.inter.IListener;
import com.vise.udp.core.inter.IThread;
import com.vise.udp.handler.ServerDiscoveryHandler;
import com.vise.udp.mode.PacketBuffer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Set;

/**
 * @Description:
 * @author: <a href="http://www.xiaoyaoyou1212.com">DAWI</a>
 * @date: 2016-12-21 16:17
 */
public class Server implements IThread {

    private final Selector selector;
    private int emptySelects;
    private UdpOperate udpOperate;
    private volatile boolean shutdown;
    private Object updateLock = new Object();
    private Thread updateThread;
    private UdpConfig udpConfig;

    public Server() {
        udpConfig = UdpConfig.getInstance();
        udpConfig.setBufferSize(UdpConstant.OBJECT_BUFFER_SIZE);
        udpConfig.setDataDispose(IData.DEFAULT);
        udpConfig.setDiscoveryHandler(ServerDiscoveryHandler.DEFAULT);
        try {
            selector = Selector.open();
        } catch (IOException ex) {
            throw new RuntimeException("Error opening selector.", ex);
        }
    }

    public UdpOperate getUdpOperate() {
        return udpOperate;
    }

    public void bind(int udpPort) throws IOException {
        bind(new InetSocketAddress(udpPort));
    }

    public void bind(InetSocketAddress udpPort) throws IOException {
        close();
        synchronized (updateLock) {
            selector.wakeup();
            try {
                if (udpPort != null) {
                    udpOperate = new UdpOperate(udpConfig.getDataDispose(), udpConfig.getBufferSize());
                    udpOperate.bind(selector, udpPort);
                    ViseLog.d("Accepting connections on port: " + udpPort + "/UDP");
                }
            } catch (IOException ex) {
                close();
                throw ex;
            }
        }
        ViseLog.i("Server opened.");
    }

    @Override
    public void start() {
        new Thread(this, "Server").start();
    }

    @Override
    public void stop() {
        if (shutdown) return;
        close();
        ViseLog.d("Server thread stopping.");
        shutdown = true;
    }

    @Override
    public void close() {
        if (udpOperate != null) {
            udpOperate.close();
            udpOperate = null;
        }
        synchronized (updateLock) {
        }
        selector.wakeup();
        try {
            selector.selectNow();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void update(int timeout) throws IOException {
        updateThread = Thread.currentThread();
        synchronized (updateLock) {
        }
        long startTime = System.currentTimeMillis();
        int select = 0;
        if (timeout > 0) {
            select = selector.select(timeout);
        } else {
            select = selector.selectNow();
        }
        if (select == 0) {
            emptySelects++;
            if (emptySelects == 100) {
                emptySelects = 0;
                long elapsedTime = System.currentTimeMillis() - startTime;
                try {
                    if (elapsedTime < 25) Thread.sleep(25 - elapsedTime);
                } catch (InterruptedException ex) {
                }
            }
        } else {
            emptySelects = 0;
            Set<SelectionKey> keys = selector.selectedKeys();
            synchronized (keys) {
                outer:
                for (Iterator<SelectionKey> iter = keys.iterator(); iter.hasNext(); ) {
                    keepAlive();
                    SelectionKey selectionKey = iter.next();
                    iter.remove();
                    UdpOperate fromUdpOperate = (UdpOperate) selectionKey.attachment();
                    try {
                        if (udpOperate == null) {
                            selectionKey.channel().close();
                            continue;
                        }
                        InetSocketAddress fromAddress;
                        try {
                            fromAddress = udpOperate.readFromAddress();
                        } catch (IOException ex) {
                            ViseLog.e("Error reading UDP data." + ex);
                            continue;
                        }
                        if (fromAddress == null) continue;
                        PacketBuffer packetBuffer;
                        try {
                            int ops = selectionKey.readyOps();
                            if ((ops & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
                                packetBuffer = udpOperate.readPacketBuffer();
                                ViseLog.d(this + " received UDP: " + packetBuffer);
                            }
                        } catch (IOException ex) {
                            if (fromUdpOperate != null) {
                                ViseLog.e("Error reading UDP from connection: " + fromUdpOperate + ex);
                            } else {
                                ViseLog.e("Error reading UDP from unregistered address: " + fromAddress + ex);
                            }
                            continue;
                        }
                    } catch (CancelledKeyException ex) {
                        selectionKey.channel().close();
                    }
                }
            }
        }
    }

    private void keepAlive() throws IOException {
        long time = System.currentTimeMillis();
        if (udpOperate != null && udpOperate.needsKeepAlive(time)) {
            PacketBuffer packetBuffer = new PacketBuffer();
            packetBuffer.setCommand(new KeepAlive());
            udpOperate.send(packetBuffer);
        }
    }

    @Override
    public void addListener(IListener listener) {
        if (udpOperate != null) {
            udpOperate.addListener(listener);
        }
        ViseLog.d("Server listener added.");
    }

    @Override
    public void removeListener(IListener listener) {
        if (udpOperate != null) {
            udpOperate.removeListener(listener);
        }
        ViseLog.d("Server listener removed.");
    }

    @Override
    public Thread getUpdateThread() {
        return updateThread;
    }

    @Override
    public IData getDataDispose() {
        return udpConfig.getDataDispose();
    }

    @Override
    public void run() {
        ViseLog.d("Server thread started.");
        shutdown = false;
        while (!shutdown) {
            try {
                update(250);
            } catch (IOException ex) {
                ViseLog.e("Error updating server connections." + ex);
                close();
            }
        }
        ViseLog.d("Server thread stopped.");
    }

    public void dispose() throws IOException {
        close();
        selector.close();
    }
}
