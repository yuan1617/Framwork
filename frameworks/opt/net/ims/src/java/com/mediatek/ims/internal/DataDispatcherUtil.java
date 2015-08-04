package com.mediatek.ims.internal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import com.mediatek.ims.ImsAdapter;
import com.mediatek.ims.ImsAdapter.VaSocketIO;
import com.mediatek.ims.ImsAdapter.VaEvent;
import com.mediatek.ims.ImsEventDispatcher;
import com.mediatek.ims.VaConstants;
import com.mediatek.xlog.Xlog;


import java.util.HashMap;
import java.util.Arrays;
import java.util.ArrayList;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.os.IBinder;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.os.AsyncResult;
import android.os.ServiceManager;
import android.os.INetworkManagementService;
import android.os.RemoteException;

import android.net.MobileDataStateTracker;

//import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
//import com.android.internal.telephony.PhoneFactory;
//import com.android.internal.telephony.dataconnection.DataConnection;
import com.android.internal.telephony.DctConstants;

import com.mediatek.internal.telephony.DedicateBearerProperties;
import com.mediatek.internal.telephony.DefaultBearerConfig;
import com.mediatek.internal.telephony.PcscfInfo;
import com.mediatek.internal.telephony.QosStatus;
import com.mediatek.internal.telephony.TftStatus;
import com.mediatek.internal.telephony.PacketFilterInfo;
import com.mediatek.internal.telephony.TftParameter;
import com.mediatek.internal.telephony.TftAuthToken;
import com.mediatek.internal.telephony.PcscfAddr;

//import com.android.ims.IImsManagerService;

public class DataDispatcherUtil {
    protected static final String TAG = "GSM";

    static final int IMC_MAX_PACKET_FILTER_NUM = 16;
    static final int IMC_MAX_REMOTE_ADDR_AND_MASK_LEN = 32;
    static final int IMC_MAX_AUTHTOKEN_FLOWID_NUM = 4;
    static final int IMC_MAX_AUTHORIZATION_TOKEN_LEN = 16;
    static final int IMC_MAX_FLOW_IDENTIFIER_NUM = 4;
    static final int IMC_MAX_CONCATENATED_NUM = 11;
    static final int IMC_MAXIMUM_NW_IF_NAME_STRING_SIZE = 100;
    static final int IMC_PCSCF_MAX_NUM = 10;
    static final int IMC_IPV4_ADDR_LEN = 0x04;
    static final int IMC_IPV6_ADDR_LEN = 0x10;

    static final boolean DBG = true;

    public DataDispatcherUtil() {
    }

    static DedicateBearerProperties readDedicateBearer(VaEvent event) {
        //imcf_uint8 context_id
        //imcf_uint8 primary_context_id
        //imcf_uint8 ebi
        //imcf_uint8 qos_mod
        //imc_eps_qos_struct nw_assigned_eps_qos
        //imc_concatenated_msg_type_enum msg_type
        //imcf_uint8 tft_mod
        //imcf_uint8 signaling_flag
        //imcf_uint8 pcscf_mod
        //imc_tft_info_struct nw_assigned_tft
        //imc_pcscf_list_struct pcscf_list

        DedicateBearerProperties property = new DedicateBearerProperties();
        property.cid = event.getByte();
        property.defaultCid = event.getByte();
        property.bearerId = event.getByte();

        boolean hasQos = event.getByte() == 1 ? true : false;
        QosStatus qosStatus = readQos(event);
        property.qosStatus= hasQos ? qosStatus : null;

        event.getByte(); //msg

        boolean hasTft = event.getByte() == 1 ? true : false;
        property.signalingFlag = event.getByte();
        boolean hasPcscf = event.getByte() == 1 ? true : false;

        TftStatus tftStatus = readTft(event);
        PcscfInfo pcscfInfo = readPcscf(event);
        property.tftStatus = hasTft ? tftStatus : null;
        property.pcscfInfo = hasPcscf ? pcscfInfo : null;
        return property;
    }

    static void writeDedicateBearer(VaEvent event, int type, DedicateBearerProperties property) {
        event.putByte(property.cid);
        event.putByte(property.defaultCid);
        event.putByte(property.bearerId);
        event.putByte(property.qosStatus == null ? 0 : 1);
        writeQos(event, property.qosStatus == null ? new QosStatus() : property.qosStatus);
        event.putByte(type);
        event.putByte(property.tftStatus == null ? 0 : 1);
        event.putByte(property.signalingFlag);
        event.putByte(property.pcscfInfo == null ? 0 : 1);
        writeTft(event, property.tftStatus == null ? new TftStatus() : property.tftStatus);
        writePcscf(event, property.pcscfInfo == null ? new PcscfInfo() : property.pcscfInfo);
    }

    static QosStatus readQos(VaEvent event) {
        //imcf_uint8 qci;
        //imcf_uint8 gbr_present;
        //imcf_uint8 mbr_present;
        //imcf_uint8 pad[1];
        //imcf_uint32 dl_gbr;
        //imcf_uint32 ul_gbr;
        //imcf_uint32 dl_mbr;
        //imcf_uint32 ul_mbr;

        QosStatus qosStatus = new QosStatus();
        qosStatus.qci = event.getByte();
        boolean isGbrPresent = event.getByte() == 1;
        boolean isMbrPresent = event.getByte() == 1;
        event.getByte(); //padding
        qosStatus.dlGbr= event.getInt();
        qosStatus.ulGbr= event.getInt();
        qosStatus.dlMbr= event.getInt();
        qosStatus.ulMbr= event.getInt();
        return qosStatus;
    }

    static void writeQos(VaEvent event, QosStatus qosStatus) {
        event.putByte(qosStatus.qci);
        event.putByte(qosStatus.dlGbr > 0 && qosStatus.ulGbr > 0 ? 1 : 0);
        event.putByte(qosStatus.dlMbr > 0 && qosStatus.ulMbr > 0 ? 1 : 0);
        event.putByte(0); //padding
        event.putInt(qosStatus.dlGbr);
        event.putInt(qosStatus.ulGbr);
        event.putInt(qosStatus.dlMbr);
        event.putInt(qosStatus.ulMbr);
    }

    static TftStatus readTft(VaEvent event) {
        //imc_tft_operation_enum tft_opcode
        //imcf_uint8 ebit_flag
        //imcf_uint8 pad[2];
        //imc_pkt_filter_struct pf_list [IMC_MAX_PACKET_FILTER_NUM]
        //imc_tft_parameter_list_struct parameter_list

        TftStatus tftStatus = new TftStatus();
        tftStatus.operation = event.getByte();
        boolean ebitFlag = event.getByte() == 1;
        event.getBytes(2); //padding
        for (int i=0; i<IMC_MAX_PACKET_FILTER_NUM; i++) {
            //====imc_pkt_filter_struct====
            //imcf_uint8 id
            //imcf_uint8 precedence
            //imc_pf_direction_enum direction
            //imcf_uint8 nw_id
            //imcf_uint32 bitmap
            //imcf_uint8 remote_addr_and_mask [IMC_MAX_REMOTE_ADDR_AND_MASK_LEN]
            //imcf_uint8 protocol_nxt_hdr
            //imcf_uint8 pad2 [3]
            //imcf_uint16 local_port_low
            //imcf_uint16 local_port_high
            //imcf_uint16 remote_port_low
            //imcf_uint16 remote_port_high
            //imcf_uint32 spi
            //imcf_uint8 tos
            //imcf_uint8 tos_msk
            //imcf_uint8 pad3 [2]
            //imcf_uint32 flow_label

            PacketFilterInfo pkFilterInfo = new PacketFilterInfo();
            pkFilterInfo.id = event.getByte();
            pkFilterInfo.precedence = event.getByte();
            pkFilterInfo.direction = event.getByte();
            pkFilterInfo.networkPfIdentifier = event.getByte();
            pkFilterInfo.bitmap= event.getInt();

            byte[] addrAndMaskArray = event.getBytes(IMC_MAX_REMOTE_ADDR_AND_MASK_LEN);
            if ((pkFilterInfo.bitmap & PacketFilterInfo.IMC_BMP_V4_ADDR) > 0) { //IPv4
                try {
                    InetAddress address = InetAddress.getByAddress(Arrays.copyOfRange(addrAndMaskArray, 0, 4));
                    InetAddress mask = InetAddress.getByAddress(Arrays.copyOfRange(addrAndMaskArray, 4, 8));
                    pkFilterInfo.address = address.getHostAddress();
                    pkFilterInfo.mask = mask.getHostAddress();
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                } catch (NullPointerException e) {
                    e.printStackTrace();
                }
            } else if ((pkFilterInfo.bitmap & PacketFilterInfo.IMC_BMP_V6_ADDR) > 0) { //IPv6
                try {
                    InetAddress address = InetAddress.getByAddress(Arrays.copyOfRange(addrAndMaskArray, 0, 16));
                    InetAddress mask = InetAddress.getByAddress(Arrays.copyOfRange(addrAndMaskArray, 16, 32));
                    pkFilterInfo.address = address.getHostAddress();
                    pkFilterInfo.mask = mask.getHostAddress();
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                } catch (NullPointerException e) {
                    e.printStackTrace();
                }
            }

            pkFilterInfo.protocolNextHeader = event.getByte();
            event.getBytes(3); //padding
            pkFilterInfo.localPortLow = event.getShort();
            pkFilterInfo.localPortHigh= event.getShort();
            pkFilterInfo.remotePortLow = event.getShort();
            pkFilterInfo.remotePortHigh= event.getShort();
            pkFilterInfo.spi = event.getInt();
            pkFilterInfo.tos = event.getByte();
            pkFilterInfo.tosMask = event.getByte();
            event.getBytes(2); //padding
            pkFilterInfo.flowLabel = event.getInt();

            if (pkFilterInfo.id > 0)
                tftStatus.packetFilterInfoList.add(pkFilterInfo);
        }

        //====imc_tft_parameter_list_struct====
        //imcf_uint8 linked_pf_id_num
        //imcf_uint8 pad [3]
        //imcf_uint8 linked_pf_id_list [IMC_MAX_PACKET_FILTER_NUM]
        //imcf_uint8 authtoken_flowid_num
        //imcf_uint8 pad2 [3]
        //imc_tft_authtoken_flowid_struct authtoken_flowid_list [IMC_MAX_AUTHTOKEN_FLOWID_NUM]

        TftParameter tftParameter = new TftParameter();
        int linkedPfNumber = event.getByte();
        event.getBytes(3); //padding
        byte[] linkedPfIdArray = event.getBytes(IMC_MAX_PACKET_FILTER_NUM);
        if (linkedPfIdArray != null) {
            for (int i = 0; i < linkedPfNumber; i++) {
                tftParameter.linkedPacketFilterIdList.add(linkedPfIdArray[i] & 0xFF);
            }
        }

        int authtokenFlowIdNum = event.getByte();
        event.getBytes(3); //padding

        //====imc_tft_authtoken_flowid_struct====
        //imcf_uint8 auth_token_len
        //imcf_uint8 pad [3]
        //imcf_uint8 auth_token [IMC_MAX_AUTHORIZATION_TOKEN_LEN]
        //imcf_uint8 flow_id_num
        //imcf_uint8 pad2 [3]
        //imcf_uint8 flow_id_list [IMC_MAX_FLOW_IDENTIFIER_NUM][IMC_FLOW_IDENTIFIER_LEN]
        for (int i=0; i<IMC_MAX_AUTHTOKEN_FLOWID_NUM; i++) {
            TftAuthToken authToken = new TftAuthToken();
            int authTokenLength = event.getByte();
            event.getBytes(3); //padding
            byte[] authTokenArray = event.getBytes(IMC_MAX_AUTHORIZATION_TOKEN_LEN);
            if (authTokenArray != null) {
                for (int j = 0; j < IMC_MAX_AUTHORIZATION_TOKEN_LEN; j++) {
                    if (j < authTokenLength) {
                        authToken.authTokenList.add(authTokenArray[j] & 0xFF);
                    }
                }
            }

            int flowIdLength = event.getByte();
            event.getBytes(3); //padding
            for (int j=0; j<IMC_MAX_FLOW_IDENTIFIER_NUM; j++) {
                byte[] flowIdArray = event.getBytes(TftAuthToken.FLOWID_LENGTH);
                Integer[] flowIds = new Integer[TftAuthToken.FLOWID_LENGTH];
                if (flowIdArray != null) {
                    for (int k = 0; k < TftAuthToken.FLOWID_LENGTH; k++) {
                        flowIds[k] = flowIdArray[k] & 0xFF;
                    }
                }

                if (j < flowIdLength)
                    authToken.flowIdList.add(flowIds);
            }

            if (i < authtokenFlowIdNum)
                tftParameter.authTokenFlowIdList.add(authToken);
        }

        if (ebitFlag)
            tftStatus.tftParameter = tftParameter;

        return tftStatus;
    }

    static void writeTft(VaEvent event, TftStatus tftStatus) {
        event.putByte(tftStatus.operation);
        event.putByte(tftStatus.tftParameter.isEmpty() ? 0 : 1);
        event.putBytes(new byte[2]); //padding
        for (int i=0; i<IMC_MAX_PACKET_FILTER_NUM; i++) {
            PacketFilterInfo pkFilterInfo = null;
            for (PacketFilterInfo pkt : tftStatus.packetFilterInfoList) {
                if (pkt.id == i+1) {
                    pkFilterInfo = pkt;
                    break;
                }
            }

            if (pkFilterInfo == null)
                pkFilterInfo = new PacketFilterInfo();

            event.putByte(pkFilterInfo.id);
            event.putByte(pkFilterInfo.precedence);
            event.putByte(pkFilterInfo.direction);
            event.putByte(pkFilterInfo.networkPfIdentifier);
            event.putInt(pkFilterInfo.bitmap);

            byte[] addrAndMaskArray = new byte[IMC_MAX_REMOTE_ADDR_AND_MASK_LEN];
            byte[] addressByteArray = null;
            byte[] maskByteArray = null;
            if (pkFilterInfo.address != null && pkFilterInfo.address.length() > 0) {
                String[] splitArray = pkFilterInfo.address == null ? null : pkFilterInfo.address.split("\\.");
                if (splitArray != null) {
                    addressByteArray = new byte[splitArray.length];
                    for (int j=0; j<splitArray.length; j++) {
                        if (splitArray[j].length() > 0)
                            addressByteArray[j] = (byte) (Integer.parseInt(splitArray[j]) & 0xFF);
                        else
                            addressByteArray[j] = 0;
                    }

                    for (int j=0; j<addressByteArray.length; j++)
                        addrAndMaskArray[j] = addressByteArray[j];
                }
            }

            if (pkFilterInfo.mask != null && pkFilterInfo.address.length() > 0) {
                String[] splitArray = pkFilterInfo.mask == null ? null : pkFilterInfo.mask.split("\\.");
                if (splitArray != null) {
                    maskByteArray = new byte[splitArray.length];
                    for (int j=0; j<splitArray.length; j++) {
                        if (splitArray[j].length() > 0)
                            maskByteArray[j] = (byte) (Integer.parseInt(splitArray[j]) & 0xFF);
                        else if (addressByteArray != null) {
                            addressByteArray[j] = 0;
                        }
                    }

                    if ((pkFilterInfo.bitmap & PacketFilterInfo.IMC_BMP_V4_ADDR) > 0) { //IPv4
                        for (int j=0; j<maskByteArray.length; j++)
                            addrAndMaskArray[j+4] = maskByteArray[j];
                    } else if ((pkFilterInfo.bitmap & PacketFilterInfo.IMC_BMP_V6_ADDR) > 0) {//IPv6
                        for (int j=0; j<maskByteArray.length; j++)
                            addrAndMaskArray[j+16] = maskByteArray[j];
                    }
                }
            }
            event.putBytes(addrAndMaskArray);

            event.putByte(pkFilterInfo.protocolNextHeader);
            event.putBytes(new byte[3]); //padding
            event.putShort(pkFilterInfo.localPortLow);
            event.putShort(pkFilterInfo.localPortHigh);
            event.putShort(pkFilterInfo.remotePortLow);
            event.putShort(pkFilterInfo.remotePortHigh);
            event.putInt(pkFilterInfo.spi);
            event.putByte(pkFilterInfo.tos);
            event.putByte(pkFilterInfo.tosMask);
            event.putBytes(new byte[2]); //padding
            event.putInt(pkFilterInfo.flowLabel);
        }
        
        event.putByte(tftStatus.tftParameter.linkedPacketFilterIdList.size());
        event.putBytes(new byte[3]); //padding
        for (int i=0; i<IMC_MAX_PACKET_FILTER_NUM; i++) {
            if (i < tftStatus.tftParameter.linkedPacketFilterIdList.size())
                event.putByte(tftStatus.tftParameter.linkedPacketFilterIdList.get(i).byteValue());
            else
                event.putByte(0);
        }

        event.putByte(tftStatus.tftParameter.authTokenFlowIdList.size());
        event.putBytes(new byte[3]); //padding

        for (int i=0; i<IMC_MAX_AUTHTOKEN_FLOWID_NUM; i++) {
            TftAuthToken authToken = null;
            if (i < tftStatus.tftParameter.authTokenFlowIdList.size())
                authToken = tftStatus.tftParameter.authTokenFlowIdList.get(i);
            else
                authToken = new TftAuthToken();

            event.putByte(authToken.authTokenList.size());
            event.putBytes(new byte[3]); //padding
            for (int j=0; j<IMC_MAX_AUTHORIZATION_TOKEN_LEN; j++) {
                if (j < authToken.authTokenList.size())
                    event.putByte(authToken.authTokenList.get(j));
                else
                    event.putByte(0);
            }

            event.putByte(authToken.flowIdList.size());
            event.putBytes(new byte[3]); //padding
            for (int j=0; j<IMC_MAX_FLOW_IDENTIFIER_NUM; j++) {
                if (j < authToken.flowIdList.size()) {
                    for (int k=0; k<TftAuthToken.FLOWID_LENGTH; k++)
                        event.putByte(authToken.flowIdList.get(j)[k]);
                } else {
                    event.putBytes(new byte[TftAuthToken.FLOWID_LENGTH]);
                }
            }

        }
    }

    static PcscfInfo readPcscf(VaEvent event) {
        //====imc_pcscf_list_struct====
        //imcf_uint8 num_of_ipv4_pcscf_addr
        //imcf_uint8 pad [3]
        //imc_pcscf_ipv4_struct pcscf_v4 [IMC_PCSCF_MAX_NUM]
        //imcf_uint8 num_of_ipv6_pcscf_addr
        //imcf_uint8 pad2 [3]
        //imc_pcscf_ipv6_struct pcscf_v6 [IMC_PCSCF_MAX_NUM]

        PcscfInfo pcscfInfo = new PcscfInfo();
        int v4AddrNum = event.getByte();
        event.getBytes(3); //padding
        for (int i=0; i<IMC_PCSCF_MAX_NUM; i++) {
            PcscfAddr pcscfAddr = new PcscfAddr();
            //====imc_pcscf_ipv4_struct ====
            //imcf_uint8 protocol_type
            //imcf_uint8 pad [1]
            //imcf_uint16 port_num
            //imcf_uint8 addr[IMC_IPV4_ADDR_LEN]
            pcscfAddr.protocol = event.getByte();
            event.getByte(); //padding
            pcscfAddr.port = event.getShort();

            StringBuffer ipBuffer = new StringBuffer(IMC_IPV4_ADDR_LEN);
            for (int j=0; j<IMC_IPV4_ADDR_LEN; j++) {
                if (j != 0)    
                    ipBuffer.append("." + event.getByte());
                else
                    ipBuffer.append(event.getByte());
            }
            pcscfAddr.address = ipBuffer.toString();

            if (i < v4AddrNum && pcscfAddr.address != null)
                pcscfInfo.v4AddrList.add(pcscfAddr);
        }

        int v6AddrNum = event.getByte();
        event.getBytes(3); //padding
        for (int i=0; i<IMC_PCSCF_MAX_NUM; i++) {
            PcscfAddr pcscfAddr = new PcscfAddr();
            //====imc_pcscf_ipv6_struct ====
            //imcf_uint8 protocol_type
            //imcf_uint8 pad [1]
            //imcf_uint16 port_num
            //imcf_uint8 addr [IMC_IPV6_ADDR_LEN]
            pcscfAddr.protocol = event.getByte();
            event.getByte(); //padding
            pcscfAddr.port = event.getShort();

            StringBuffer ipBuffer = new StringBuffer(IMC_IPV6_ADDR_LEN);
            for (int j=0; j<IMC_IPV6_ADDR_LEN; j++) {
                if (j != 0)    
                    ipBuffer.append("." + event.getByte());
                else
                    ipBuffer.append(event.getByte());
            }
            pcscfAddr.address = ipBuffer.toString();

            if (i < v6AddrNum && pcscfAddr.address != null)
                pcscfInfo.v6AddrList.add(pcscfAddr);
        }
        return pcscfInfo;
    }

    static void writePcscf(VaEvent event, PcscfInfo pcscfInfo) {
        event.putByte(pcscfInfo.v4AddrList.size());
        event.putBytes(new byte[3]); //padding
        for (int i=0; i<IMC_PCSCF_MAX_NUM; i++) {
            PcscfAddr pcscfAddr = null;
            if (i < pcscfInfo.v4AddrList.size())
                pcscfAddr = pcscfInfo.v4AddrList.get(i);
            else
                pcscfAddr = new PcscfAddr();

            event.putByte(pcscfAddr.protocol);
            event.putByte(0); //padding
            event.putShort(pcscfAddr.port);

            String[] pcscfSplitArray = pcscfAddr.address == null ? null : pcscfAddr.address.split("\\.");
            for (int j=0; j<IMC_IPV4_ADDR_LEN; j++) {
                if (pcscfSplitArray != null && j < pcscfSplitArray.length)
                    event.putByte(Integer.parseInt(pcscfSplitArray[j]));
                else
                    event.putByte(0);
            }
        }

        event.putByte(pcscfInfo.v6AddrList.size());
        event.putBytes(new byte[3]); //padding
        for (int i=0; i<IMC_PCSCF_MAX_NUM; i++) {
            PcscfAddr pcscfAddr = null;
            if (i < pcscfInfo.v6AddrList.size())
                pcscfAddr = pcscfInfo.v6AddrList.get(i);
            else
                pcscfAddr = new PcscfAddr();

            event.putByte(pcscfAddr.protocol);
            event.putByte(0); //padding
            event.putShort(pcscfAddr.port);

            String[] pcscfSplitArray = pcscfAddr.address == null ? null : pcscfAddr.address.split("\\.");
            for (int j=0; j<IMC_IPV6_ADDR_LEN; j++) {
                if (pcscfSplitArray != null && j < pcscfSplitArray.length)
                    event.putByte(Integer.parseInt(pcscfSplitArray[j]));
                else
                    event.putByte(0);
            }
        }
    }
    static void dumpPdnAckRsp(VaEvent event) {
        String functionName = "[dumpPdnAckRsp] ";
        int transactionId;
        int pdnCnt = 0;
        byte [] pad2 = new byte[2];

        transactionId = event.getByte();
        pdnCnt = event.getByte();
        pad2 = event.getBytes(pad2.length);
        
        log(functionName + "transactionId: " + transactionId + ", pdn cnt: " + pdnCnt);
    }

    static void dumpPdnContextProp(VaEvent event) {
        final int nPAD3LEN = 3;
        byte [] pad3 = new byte[nPAD3LEN];
        String functionName = "[dumpPdnContextProp] ";

        //Bearers properties
        int addrType;
        DedicateBearerProperties property;

        addrType = event.getByte();
        pad3 = event.getBytes(nPAD3LEN);

        property = readDedicateBearer(event);

        log(functionName + "pdn_contexts, addrType: " + addrType + ", cid: " + property.cid
            + ", defaultCid: " + property.defaultCid + ", bearerId: " + property.bearerId
            + ", Qos: " + property.qosStatus + ", signalingFlag: " + property.signalingFlag
            + ", tft: " + property.tftStatus + ", pcscf:" + property.pcscfInfo);

        int num_of_concatenated_contexts = event.getByte();
        pad3 = event.getBytes(nPAD3LEN);                            // padding

        log(functionName + "concatenated num: " + num_of_concatenated_contexts);
        // concatenated properties
        for (int i = 0; i < IMC_MAX_CONCATENATED_NUM; i++) {    // write concatenated contexts    
            if (i < num_of_concatenated_contexts) {
                property = readDedicateBearer(event);
                log(functionName + "concatenated contexts[: " + i + "], cid: " 
                + property.cid + ", defaultCid: " + property.defaultCid + ", bearerId: "
                + property.bearerId + ", Qos: " + property.qosStatus + ", signalingFlag: "
                + property.signalingFlag + ", tft: " + property.tftStatus + ", pcscf:" + property.pcscfInfo);
            } 
        }
    }

    static void writeAllBearersProperties(VaEvent event, int msgType, int pdp_addr_type, DedicateBearerProperties property) {
        //imc_pdn_context_struct contexts 
        //----------------------------------------------
        //imc_pdp_addr_type_enum pdp_addr_type
        //imcf_uint8 pad2[3]
        //imc_single_concatenated_msg_struct main_context
        //imcf_uint8 num_of_concatenated_contexts
        //imcf_uint8 pad3[3]
        //imc_single_concatenated_msg_struct concatenated_context[IMC_MAX_CONCATENATED_NUM];
        //----------------------------------------------
        int num_of_concatenated_contexts = property.concatenateBearers.size();

        event.putByte(pdp_addr_type);   //temporarily for imc_pdp_addr_type_enum
        event.putBytes(new byte[3]);    //padding

        writeDedicateBearer(event, msgType, property);    // write main_context
        event.putByte(num_of_concatenated_contexts);            // write concatenated number             
        event.putBytes(new byte[3]);                            // padding
        
        for (int i = 0; i < IMC_MAX_CONCATENATED_NUM; i++) {    // write concatenated contexts    
            if (i < num_of_concatenated_contexts)
                writeDedicateBearer(event, msgType, property.concatenateBearers.get(i));
            else
                writeDedicateBearer(event, msgType, new DedicateBearerProperties());
        }

        if (DBG) dumpPdnContextProp(event);
    }

    DefaultPdnActInd extractDefaultPdnActInd(VaEvent event) {
        DefaultPdnActInd defaultPdnActInd = new DefaultPdnActInd();

        defaultPdnActInd.transactionId = event.getByte();
        defaultPdnActInd.pad = event.getBytes(defaultPdnActInd.pad.length);   //skip pad size 3
        defaultPdnActInd.qosStatus = DataDispatcherUtil.readQos(event);
        defaultPdnActInd.emergency_ind = event.getByte();
        defaultPdnActInd.pcscf_discovery = event.getByte();
        defaultPdnActInd.signalingFlag = (event.getByte() > 0) ? 1: 0;
        defaultPdnActInd.pad2 = event.getBytes(defaultPdnActInd.pad2.length);   //skip pad size 1

        log("extractDefaultPdnActInd DefaultPdnActInd" + defaultPdnActInd);
        return defaultPdnActInd;
    }

    PdnDeactInd extractPdnDeactInd(VaEvent event) {
        PdnDeactInd pdnDeactInd = new PdnDeactInd();
        //imcf_uint8    transaction_id
        //imcf_uint8    abort_activate_transaction_id
        //imcf_uint8    context_id_is_valid
        //imcf_uint8    context_id
      
        pdnDeactInd.transactionId = event.getByte();
        pdnDeactInd.abortTransactionId = event.getByte();
        pdnDeactInd.isCidValid = (event.getByte() == 1);
        pdnDeactInd.cid = event.getByte();

        log("extractDefaultPdnActInd PdnDeactInd" + pdnDeactInd);
        return pdnDeactInd;
    }

    VaEvent composeGlobalIPAddrVaEvent(int MsgId, int cid, int networkId, byte [] addr, String intfName) {
        // imcf_uint8                               context_id
        // imcf_uint8 pad[3]                     padding 3 bytes
        // imcf_int32 network_id            network id for binding interface with ip address
        // imcf_uint8                               global_ipv4_addr[0x04] or global_ipv6_address[0x10]
        // char                                       nw_if_name[100]  
        VaEvent event = new VaEvent(MsgId);
        final int intfNamMaxLen = 100;         
        event.putByte(cid);
        event.putBytes(new byte[3]); //padding
        event.putInt(networkId);
        event.putBytes(addr);
        event.putString(intfName, intfNamMaxLen);

        return event;
    }

    // TODO: add this code later [start]
    /*
    IImsManagerService getImsService() {
        IImsManagerService service = null;
        int retryCount = 0;
        do {
            try {
                IBinder b = ServiceManager.getService(Context.IMS_SERVICE);
                service = IImsManagerService.Stub.asInterface(b);
                if (service == null) {
                    loge("getImsService IBinder is null");
                    Thread.sleep(500);
                    retryCount++;
                }
            } catch(Exception e) {
                e.printStackTrace();
            }
        } while (service == null && retryCount < 6);

        return service;
    }
    */
    // TODO: add this code later [end]

    static void log(String text) {
        Xlog.d(TAG, "[dedicate] DataDispatcherUtil " + text);
    }

    private static void loge(String text) {
        Xlog.e(TAG, "[dedicate] DataDispatcherUtil " + text);
    }

    public class DefaultPdnActInd {
        // imcf_uint8                               transaction_id
        // imcf_uint8                               pad[3]
        // imc_eps_qos_struct                  ue_defined_eps_qos
        // imc_emergency_ind_enum       emergency_indidation
        // imc_pcscf_discovery_enum       pcscf_discovery_flag
        // imcf_uint8                               signaling_flag
        // imcf_uint8                               pad2[1]
        public int transactionId;
        public byte [] pad = new byte [3];
        public QosStatus qosStatus;
        public int emergency_ind;
        public int pcscf_discovery;
        public int signalingFlag;
        public byte [] pad2 = new byte [1];

        @Override
        public String toString() {
            return "[transactionId=" + transactionId + ", Qos" + qosStatus + ", emergency_ind=" + emergency_ind +
                ", pcscf_discorvery=" + pcscf_discovery + ", signalingFlag=" + signalingFlag + "]";
        }
    }
    
    public class PdnDeactInd {
        // imcf_uint8                               transaction_id
        // imcf_uint8                               abort_activate_transaction_id
        // imcf_uint8                               context_id_is_valid
        // imcf_uint8                               context_id
        public int transactionId;
        public int abortTransactionId;
        public boolean isCidValid;
        public int cid;

        @Override
        public String toString() {
            return "[transactionId=" + transactionId + ", abortTransactionId=" + abortTransactionId + ", isCidValid=" + isCidValid + ", cid=" + cid + "]";
        }
    }
}
