package com.zhenwu.api.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * @author zhenwu
 */
@Data
@EqualsAndHashCode
public class RequestData implements Serializable {

    private static final long serialVersionUID = 6972259853636371532L;

    /**
     * 用户访问接口标识
     */
    private String interfaceToken;

    /**
     * 接口要上送的数据
     */
    private String interfaceData;

    /**
     * 签名
     */
    private String sign;
}
