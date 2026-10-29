package com.unihub.notification.web.dto;

import com.unihub.shared.web.dto.PageMeta;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(
        types = {"object"},
        requiredProperties = {"items", "page"})
public record NotificationListResponse(List<Notification> items, PageMeta page) {}
