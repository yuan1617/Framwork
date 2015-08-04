LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	IGraphicBufferConsumer.cpp \
	IConsumerListener.cpp \
	BitTube.cpp \
	BufferItem.cpp \
	BufferItemConsumer.cpp \
	BufferQueue.cpp \
	BufferQueueConsumer.cpp \
	BufferQueueCore.cpp \
	BufferQueueProducer.cpp \
	BufferSlot.cpp \
	ConsumerBase.cpp \
	CpuConsumer.cpp \
	DisplayEventReceiver.cpp \
	GLConsumer.cpp \
	GraphicBufferAlloc.cpp \
	GuiConfig.cpp \
	IDisplayEventConnection.cpp \
	IGraphicBufferAlloc.cpp \
	IGraphicBufferProducer.cpp \
	IProducerListener.cpp \
	ISensorEventConnection.cpp \
	ISensorServer.cpp \
	ISurfaceComposer.cpp \
	ISurfaceComposerClient.cpp \
	LayerState.cpp \
	Sensor.cpp \
	SensorEventQueue.cpp \
	SensorManager.cpp \
	StreamSplitter.cpp \
	Surface.cpp \
	SurfaceControl.cpp \
	SurfaceComposerClient.cpp \
	SyncFeatures.cpp \

LOCAL_SHARED_LIBRARIES := \
	libbinder \
	libcutils \
	libEGL \
	libGLESv2 \
	libsync \
	libui \
	libutils \
	liblog

# --- MediaTek -------------------------------------------------------------------------------------
MTK_PATH = mediatek

ifneq (, $(findstring MTK_AOSP_ENHANCEMENT, $(COMMON_GLOBAL_CPPFLAGS)))
	LOCAL_SRC_FILES += \
		$(MTK_PATH)/BufferQueueDump.cpp \
		$(MTK_PATH)/BufferQueueDebug.cpp \
		$(MTK_PATH)/BufferQueueMonitor.cpp
endif

LOCAL_C_INCLUDES += \
	hardware/libhardware/include \
	$(TOP)/$(MTK_ROOT)/hardware/gralloc_extra/include \
	$(TOP)/$(MTK_ROOT)/hardware/include \
	$(TOP)/$(MTK_ROOT)/hardware/ui_ext/inc

LOCAL_CFLAGS := -DLOG_TAG=\"GLConsumer\"

LOCAL_SHARED_LIBRARIES += \
	libdl \
	libhardware \
	libui_ext \
	libgralloc_extra \
	libselinux

ifeq ($(MTK_EMULATOR_SUPPORT), yes)
	LOCAL_CFLAGS += -DMTK_EMULATOR_SUPPORT
endif # MTK_EMULATOR_SUPPORT
# --------------------------------------------------------------------------------------------------

LOCAL_MODULE:= libgui

ifeq ($(TARGET_BOARD_PLATFORM), tegra)
	LOCAL_CFLAGS += -DDONT_USE_FENCE_SYNC
endif
ifeq ($(TARGET_BOARD_PLATFORM), tegra3)
	LOCAL_CFLAGS += -DDONT_USE_FENCE_SYNC
endif

include $(BUILD_SHARED_LIBRARY)

ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call first-makefiles-under,$(LOCAL_PATH))
endif
