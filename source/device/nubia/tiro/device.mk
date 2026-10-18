#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from nubia sm8650-common
$(call inherit-product, device/nubia/sm8650-common/common.mk)

DEVICE_PATH := device/nubia/tiro

# Audio
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/audio/mixer_paths_pineapple_mtp.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_pineapple/mixer_paths_pineapple_mtp.xml

# Soong
PRODUCT_SOONG_NAMESPACES += \
    $(DEVICE_PATH)

# NubiaParts
PRODUCT_PACKAGES += \
    NubiaParts


# Basic Call Recorder
ifeq ($(strip $(TARGET_PREBUILT_BCR)),true)
PRODUCT_PACKAGES += \
    Bcr
endif

# Overlays
PRODUCT_PACKAGES += \
    SettingsProviderResTiro

# Inherit from proprietary targets
$(call inherit-product, vendor/nubia/tiro/tiro-vendor.mk)
