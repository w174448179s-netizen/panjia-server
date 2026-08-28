package com.panjia.license.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 版本范围。
 * token payload 中存储 [minVersion, maxVersion]，服务端激活/心跳时校验。
 * 防止客户用低版本授权跑高版本（规避安全修复）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class VersionRange {

    private String minVersion;
    private String maxVersion;

    /**
     * 校验指定版本是否在许可范围内。
     * @param productVersion 当前产品版本
     * @return true = 在范围内
     */
    public boolean contains(String productVersion) {
        if (productVersion == null) {
            return false;
        }
        int min = compareVersion(minVersion, productVersion);
        int max = compareVersion(productVersion, maxVersion);
        return min <= 0 && max <= 0;
    }

    /**
     * 版本比较，返回负数（v1<v2）/ 零（相等）/ 正数（v1>v2）。
     * 按点分段比较，支持 x.y.z 格式。
     */
    private int compareVersion(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            Integer n1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            Integer n2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (!n1.equals(n2)) {
                return n1.compareTo(n2);
            }
        }
        return 0;
    }
}
