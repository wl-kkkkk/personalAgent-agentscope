package com.tyut.agentscope.web;

public record ApproveRequest(String taskId, boolean approved, String note) {
}
