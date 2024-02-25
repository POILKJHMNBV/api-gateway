package com.zhenwu.api.model;

import com.zhenwu.common.entity.InnerApiTransferInterfaceInfo;
import com.zhenwu.common.entity.InnerApiTransferUser;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * @author zhenwu
 */
@Data
@EqualsAndHashCode
public class RequestInfo implements Serializable {

    private static final long serialVersionUID = -1561027761202743695L;

    private RequestData requestData;

    /**
     * 用户访问标识
     */
    private String accessKey;

    /**
     * 当前访问用户
     */
    private InnerApiTransferUser innerApiTransferUser;

    /**
     * 当前要访问的接口信息
     */
    private InnerApiTransferInterfaceInfo innerApiTransferInterfaceInfo;
}
