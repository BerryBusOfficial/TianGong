package org.wutong.tiangong.common.type.impl;

import org.wutong.tiangong.common.type.Chat;
import org.wutong.tiangong.common.type.Model;
import org.wutong.tiangong.common.type.ProviderType;

/**
 * OpenAI 本家（api.openai.com 及其兼容端点）。<br>
 * 只标了 Chat + Model：本模块目前只实现了 chat / models 两个端点，
 * embedding 虽然协议里有 {@code /v1/embeddings}，但尚未落地 —— 不标 Embedding 是为了
 * 让「不支持」这件事在类型上就说得清，而不是运行时才炸。
 */
public class Openai implements ProviderType, Chat, Model {
}
