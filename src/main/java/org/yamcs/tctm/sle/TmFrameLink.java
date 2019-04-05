package org.yamcs.tctm.sle;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.api.EventProducer;
import org.yamcs.api.EventProducerFactory;
import org.yamcs.sle.FrameConsumer;
import org.yamcs.sle.Isp1Authentication;
import org.yamcs.sle.Isp1Handler;
import org.yamcs.sle.RafServiceUserHandler;
import org.yamcs.sle.RafSleMonitor;
import org.yamcs.sle.AbstractServiceUserHandler.AuthLevel;
import org.yamcs.sle.CcsdsTime;
import org.yamcs.sle.Constants.DeliveryMode;
import org.yamcs.sle.Constants.LockStatus;
import org.yamcs.sle.Constants.RafProductionStatus;
import org.yamcs.tctm.AggregatedDataLink;
import org.yamcs.tctm.Link;
import org.yamcs.tctm.TcTmException;
import org.yamcs.tctm.ccsds.MasterChannelFrameHandler;
import org.yamcs.tctm.ccsds.VirtualChannelHandler;
import org.yamcs.utils.StringConverter;

import com.google.common.util.concurrent.AbstractService;

import ccsds.sle.transfer.service.raf.outgoing.pdus.RafStatusReportInvocation;
import ccsds.sle.transfer.service.raf.outgoing.pdus.RafTransferDataInvocation;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * Receives TM frames via SLE.
 * The Virtual Channel configuration is identical with the configuration of {@link TmFrameLink}.
 * <p>
 * The SLE specific settings are:
 * <table border=1>
 * <tr>
 * <td>initiatorId</td>
 * <td>identifier of the local application</td>
 * </tr>
 * <tr>
 * <td>responderPortId</td>
 * <td>Responder Port Identifier</td>
 * </tr>
 * <tr>
 * <td>deliveryMode</td>
 * <td>one of rtnTimelyOnline, rtnCompleteOnline, rtnOffline</td>
 * </tr>
 * <tr>
 * <td>serviceInstanceNumber</td>
 * <td>Used in the bind request to select the instance number of the remote service.This number
 * together with the deliverymode specify the so called service name identifier (raf=onltX where X is
 * the number)</td>
 * </tr>
 * <tr>
 * versionNumber
 * <td>the version number is sent in the bind invocation. We only support the version of the SLE valid
 * in April-2019; however this field is not checked.</td>
 * <td></td>
 * </tr>
 * <tr>
 * <td>myUsername</td>
 * <td>username that is passed in outgoing SLE messages. A corresponding password has to be specified (in
 * hexadecimal) in the security.yaml file.</td>
 * </tr>
 * <tr>
 * <td>peerUsername</td>
 * <td>username that is used to verify the incoming SLE messages. A corresponding password has to be
 * specified (in hexadecimal) in the security.yaml file.</td>
 * </tr>
 * <tr>
 * <td>authLevel</td>
 * <td>one of NONE, BIND or ALL - it configures which incoming and outgoing PDUs contain authentication
 * information.</td>
 * </tr>
 * 
 * </table>
 * 
 * 
 * @author nm
 *
 */
public class TmFrameLink extends AbstractService implements AggregatedDataLink {
    private Logger log = LoggerFactory.getLogger(this.getClass().getName());
    String packetPreprocessorClassName;
    Object packetPreprocessorArgs;
    String yamcsInstance;
    String name;
    MasterChannelFrameHandler frameHandler;
    EventProducer eventProducer;
    YConfiguration config;
    List<Link> subLinks;
    private volatile boolean disabled = false;
    long frameCount = 0;
    RafServiceUserHandler rsuh;
    String host;
    int port;
    Isp1Authentication auth;
    DeliveryMode deliveryMode;
    String responderPortId;
    String initiatorId;
    MyConsumer frameConsumer = new MyConsumer();
    RafSleMonitor sleMonitor = new MyMonitor();
    int versionNumber;
    private AuthLevel authLevel;
    int serviceInstanceNumber;

    /**
     * Creates a new UDP Frame Data Link
     * 
     * @throws ConfigurationException
     *             if port is not defined in the configuration
     */
    public TmFrameLink(String instance, String name, YConfiguration config) throws ConfigurationException {
        this.yamcsInstance = instance;
        this.name = name;
        host = config.getString("host");
        port = config.getInt("port");

        responderPortId = config.getString("responderPortId");
        initiatorId = config.getString("initiatorId");
        deliveryMode = config.getEnum("deliveryMode", DeliveryMode.class);
        auth = getAuthentication(config);
        authLevel = config.getEnum("authLevel", AuthLevel.class);

        frameHandler = new MasterChannelFrameHandler(yamcsInstance, name, config);
        eventProducer = EventProducerFactory.getEventProducer(yamcsInstance, "SLE[" + name + "]", 10000);
        versionNumber = config.getInt("versionNumber", 5);
        serviceInstanceNumber = config.getInt("serviceInstanceNumber", 1);

        subLinks = new ArrayList<>();
        for (VirtualChannelHandler vch : frameHandler.getVcHandlers()) {
            if (vch instanceof Link) {
                Link l = (Link) vch;
                subLinks.add(l);
                l.setParent(this);
            }
        }
    }

    private Isp1Authentication getAuthentication(YConfiguration c) {
        String myUsername = c.getString("myUsername");
        String peerUsername = c.getString("peerUsername");
        String hashAlgo = c.getString("hashAlgorithm", "SHA-256");
        if (!"SHA-1".equalsIgnoreCase(hashAlgo) && !"SHA-256".equalsIgnoreCase(hashAlgo)) {
            throw new ConfigurationException(
                    "Invalid hash algorithm '" + hashAlgo + "' specified. Supported are SHA-1 and SHA-256");
        }

        YConfiguration sec = YConfiguration.getConfiguration("security");
        YConfiguration slesec = sec.getConfig("SLE");
        byte[] myPass = StringConverter.hexStringToArray(slesec.getString(myUsername));
        byte[] peerPass = StringConverter.hexStringToArray(slesec.getString(peerUsername));
        return new Isp1Authentication(myUsername, myPass, peerUsername, peerPass, hashAlgo);
    }

    @Override
    protected void doStart() {
        if (!disabled) {
            connect();
        }
        notifyStarted();
    }

    @Override
    protected void doStop() {
        if (rsuh != null) {
            rsuh.shutdown();
            rsuh = null;
        }
        notifyStopped();
    }

    private synchronized void connect() {
        log.debug("Connecting to SLE RAF service {}:{} as user {}", host, port, auth.getMyUsername());
        rsuh = new RafServiceUserHandler(auth, responderPortId, initiatorId, deliveryMode,
                serviceInstanceNumber, frameConsumer);
        rsuh.setVersionNumber(versionNumber);
        rsuh.setAuthLevel(authLevel);
        rsuh.addMonitor(sleMonitor);
        NioEventLoopGroup workerGroup = EventLoopResource.getEventLoop();
        Bootstrap b = new Bootstrap();
        b.group(workerGroup);
        b.channel(NioSocketChannel.class);
        b.option(ChannelOption.SO_KEEPALIVE, true);
        b.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(8192, 4, 4));
                ch.pipeline().addLast(new Isp1Handler(true));
                ch.pipeline().addLast(rsuh);
            }
        });
        b.connect(host, port).addListener(f -> {
            if (!f.isSuccess()) {
                eventProducer.sendWarning("Failed to connect to the SLE provider: " + f.cause().getMessage());
                rsuh = null;
            } else {
                sleBind();
            }
        });
    }

    private void sleBind() {
        rsuh.bind().handle((v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to bind: " + t.getMessage());
                return null;
            }
            sleStart();
            return null;
        });
    }

    private void sleStart() {
        rsuh.start().handle((v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to start: " + t.getMessage());
                return null;
            }
            return null;
        });
    }

    @Override
    public Status getLinkStatus() {
        if (disabled) {
            return Status.DISABLED;
        }
        if (state() == State.FAILED) {
            return Status.FAILED;
        }

        if ((rsuh != null) && rsuh.isConnected()) {
            return Status.OK;
        } else {
            return Status.UNAVAIL;
        }
    }

    @Override
    public String getDetailedStatus() {
        if (disabled) {
            return "DISABLED";
        } else {
            return "";
        }
    }

    /**
     * Sets the disabled to true such that getNextPacket ignores the received datagrams
     */
    @Override
    public synchronized void disable() {
        disabled = true;
        if (rsuh != null) {
            rsuh.shutdown();
            rsuh = null;
        }
    }

    /**
     * Sets the disabled to false such that getNextPacket does not ignore the received datagrams
     */
    @Override
    public synchronized void enable() {
        disabled = false;
        if (rsuh == null) {
            connect();
        }
    }

    @Override
    public boolean isDisabled() {
        return disabled;
    }

    @Override
    public long getDataInCount() {
        return frameCount;
    }

    @Override
    public long getDataOutCount() {
        return 0;
    }

    @Override
    public List<Link> getSubLinks() {
        return subLinks;
    }

    @Override
    public YConfiguration getConfig() {
        return config;
    }

    @Override
    public String getName() {
        return name;
    }

    class MyMonitor implements RafSleMonitor {

        @Override
        public void connected() {
            eventProducer.sendInfo("SLE connected");
        }

        @Override
        public void disconnected() {
            eventProducer.sendInfo("SLE disconnected");
            if (rsuh != null) {
                rsuh.shutdown();
                rsuh = null;
            }
        }

        @Override
        public void stateChanged(org.yamcs.sle.AbstractServiceUserHandler.State newState) {
            eventProducer.sendInfo("SLE state changed to " + newState);

        }

        @Override
        public void exceptionCaught(Throwable t) {
            log.warn("SLE exception caught", t);
            eventProducer.sendInfo("SLE exception caught: " + t.getMessage());
        }

        @Override
        public void onRafStatusReport(RafStatusReportInvocation statusReport) {
            // TODO make this into telemetry
            eventProducer.sendInfo("SLE status report: " + statusReport);
        }

    }

    class MyConsumer implements FrameConsumer {

        @Override
        public void acceptFrame(RafTransferDataInvocation rtdi) {

            if (disabled) {
                log.debug("Ignoring frame received while disabled");
                return;
            }

            byte[] data = rtdi.getData().value;
            int length = data.length;

            if (log.isTraceEnabled()) {
                log.trace("Received frame length: {}", data.length);
            }
            try {
                if (length < frameHandler.getMinFrameSize()) {
                    eventProducer.sendWarning("Error processing frame: size " + length
                            + " shorter than minimum allowed " + frameHandler.getMinFrameSize());
                } else if (length > frameHandler.getMaxFrameSize()) {
                    eventProducer.sendWarning("Error processing frame: size " + length + " longer than maximum allowed "
                            + frameHandler.getMaxFrameSize());
                } else {
                    frameCount++;

                    frameHandler.handleFrame(data, 0, length);
                }
            } catch (TcTmException e) {
                eventProducer.sendWarning("Error processing frame: " + e.toString());
            } catch (Exception e) {
                log.error("Error processing frame", e);
            }
        }

        @Override
        public void onExcessiveDataBacklog() {
            eventProducer.sendWarning("Excessive Data Backlog reported by the SLE provider");
        }

        @Override
        public void onProductionStatusChange(RafProductionStatus productionStatusChange) {
            eventProducer.sendInfo("SLE production satus changed to " + productionStatusChange);

        }

        @Override
        public void onLossFrameSync(CcsdsTime time, LockStatus carrier, LockStatus subcarrier, LockStatus symbolSync) {
            // TODO make some parameters out of this
            eventProducer.sendInfo("SLE loss frame sync time: " + time + " carrier: " + carrier + " subcarrier: "
                    + subcarrier + " symbolSync: " + symbolSync);
        }

        @Override
        public void onEndOfData() {
            eventProducer.sendInfo("SLE end of data received");
        }
    }
}
