#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from nubia sm8650-common
$(call inherit-product, device/nubia/sm8650-common/common.mk)

DEVICE_PATH := device/nubia/tiro

# Axion's HbmService is the single owner of the panel HBM node. Keeping the
# LiveDisplay sunlight HAL enabled would create a second writer and reset a
# user-selected forced state.
$(call soong_config_set_bool,livedisplay_sysfs,enable_se,false)

# Tiro-only CPU policy max frequency access for the native game limiter.
# system_ext also reaches Axion DSU builds without touching the vendor partition.
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/rootdir/etc/init.tiro.cpu-game.rc:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/init/init.tiro.cpu-game.rc

# Audio
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/audio/mixer_paths_pineapple_mtp.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_pineapple/mixer_paths_pineapple_mtp.xml

# Soong
PRODUCT_SOONG_NAMESPACES += \
    $(DEVICE_PATH)

# NubiaParts
PRODUCT_PACKAGES += \
    NubiaParts \
    tiro_hardware_service

# Overlays
PRODUCT_PACKAGES += \
    SettingsProviderResTiro \
    SettingsOverlayTiro

# Inherit from proprietary targets
$(call inherit-product, vendor/nubia/tiro/tiro-vendor.mk)

# Disable Axion kernel controls on Tiro
PRODUCT_SYSTEM_PROPERTIES += \
    ro.axion.kernel_manager.supported=false

# Native DTS:X Ultra integration
$(call inherit-product, vendor/nubia/tiro/dtsx/dtsx.mk)

# Local AxionOS build identity. The SDK reads these device properties at runtime;
# leave ROM version, date and Android version to the build system itself.
PRODUCT_SYSTEM_PROPERTIES += \
    ro.axion.local_build_type=UNOFFICIAL \
    ro.axion.local_maintainer=GAME_OVER_op


# AXION_TIRO_QCOM_SOONG_NAMESPACES
# Explicit namespaces required when building Tiro without the interactive
# Axion lunch wrapper.
PRODUCT_SOONG_NAMESPACES += \
    hardware/qcom-caf/sm8650 \
    hardware/qcom-caf/bootctrl \
    vendor/qcom/opensource/commonsys/display \
    vendor/qcom/opensource/commonsys-intf/display \
    hardware/qcom-caf/sm8650/data-ipa-cfg-mgr \
    vendor/qcom/opensource/dataservices \
    vendor/qcom/opensource/audio-hal/st-hal-ar-legacy \
    hardware/qcom-caf/thermal \
    hardware/qcom-caf/wlan \
    hardware/qcom-caf/wlan/qcwcn
