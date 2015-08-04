package com.mediatek.internal.telephony.ppl;

interface IPplAgent {
	byte[] readControlData();
	int writeControlData(in byte[] data);
}
