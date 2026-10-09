package com.equinox.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class FraudVelocityService {

    private final StringRedisTemplate redisTemplate;
    private static final int MAX_TX_PER_MIN = 5;
    private static final double MAX_VOL_PER_MIN = 10000.0;

    public boolean isFraudulent(String accountId, String txnId, BigDecimal amount) {
        long now = Instant.now().toEpochMilli();
        long windowStart = now - 60000;

        String txnKey = "fraud:txns:" + accountId;
        String volKey = "fraud:vol:" + accountId;

        redisTemplate.opsForZSet().removeRangeByScore(txnKey, 0, windowStart);
        redisTemplate.opsForZSet().add(txnKey, txnId, now);
        Long recentTxnCount = redisTemplate.opsForZSet().zCard(txnKey);
        redisTemplate.expire(txnKey, 60, TimeUnit.SECONDS);

        if (recentTxnCount != null && recentTxnCount > MAX_TX_PER_MIN) {
            return true;
        }

        String currentVolumeStr = redisTemplate.opsForValue().get(volKey);
        double currentVolume = currentVolumeStr != null ? Double.parseDouble(currentVolumeStr) : 0.0;

        if (currentVolume + amount.doubleValue() > MAX_VOL_PER_MIN) {
            return true;
        }

        redisTemplate.opsForValue().increment(volKey, amount.doubleValue());
        redisTemplate.expire(volKey, 60, TimeUnit.SECONDS);

        return false;
    }
}
