package com.koboolean.metamora.chat.service;

import com.koboolean.metamora.chat.domain.dto.ChatMessage;

import java.util.List;

public interface MessageSaveService {

    void save(ChatMessage chatMessageDto);

    List<com.koboolean.metamora.chat.domain.entity.ChatMessage> findBySenderAndReceiver(String userA, String userB);

    void deleteSenderAndReceiver(String sender, String id);

    String findByBadge(String id, String id1);
}
