DTSX_PATH := vendor/nubia/tiro/dtsx

PRODUCT_SOONG_NAMESPACES += $(DTSX_PATH)

PRODUCT_PACKAGES += \
    DtsUltra \
    dtsaudio \
    dtsx_init_rc \
    libdts-eagle-shared \
    libdtsaudio \
    libdtsdsec

PRODUCT_COPY_FILES += \
    $(DTSX_PATH)/proprietary/system/etc/permissions/dtsaudio.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/dtsaudio.xml \
    $(DTSX_PATH)/proprietary/system/etc/default-permissions/default-com.dts.dtsxultra.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/default-permissions/default-com.dts.dtsxultra.xml \
    $(DTSX_PATH)/proprietary/system/etc/sysconfig/config-com.dts.dtsxultra.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/sysconfig/config-com.dts.dtsxultra.xml

PRODUCT_COPY_FILES += $(call find-copy-subdir-files,*,$(DTSX_PATH)/proprietary/vendor/etc/dts,$(TARGET_COPY_OUT_VENDOR)/etc/dts)

# Native equivalents of the safe property part of module service.sh.
PRODUCT_SYSTEM_PROPERTIES += \
    ro.audio.ignore_effects=false \
    ro.dts.licensepath=/vendor/etc/dts/ \
    ro.dts.cfgpath=/vendor/etc/dts/ \
    ro.product.lge.globaleffect.dts=false \
    ro.lge.globaleffect.dts=false

PRODUCT_VENDOR_PROPERTIES += \
    ro.vendor.dts.licensepath=/vendor/etc/dts/ \
    ro.vendor.dts.cfgpath=/vendor/etc/dts/ \
    ro.vendor.feature.zte_feature_dts_ultra_enable=true \
    vendor.dts.audio.allow_offload=true

PRODUCT_ODM_PROPERTIES += \
    ro.odm.config.dts_licensepath=/vendor/etc/dts/
