#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

include device/nubia/sm8650-common/BoardConfigCommon.mk

# Kernel
TARGET_KERNEL_CONFIG += \
    oem/boards/tiro_diff.config

# Properties
TARGET_VENDOR_PROP += $(DEVICE_PATH)/vendor.prop

# High brightness mode
HBM_SUPPORTED := true
HBM_NODE := /sys/devices/platform/soc/soc:qcom,dsi-display-primary/hbm

# Security patch
BOOT_SECURITY_PATCH := 2025-12-01
VENDOR_SECURITY_PATCH := $(BOOT_SECURITY_PATCH)

include vendor/nubia/tiro/BoardConfigVendor.mk

# Tiro uses kernel-native performance policy.
TARGET_SUPPORTS_KERNEL_MANAGER := false
