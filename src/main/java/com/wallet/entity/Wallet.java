package com.wallet.entity;

import java.util.UUID;

public record Wallet (UUID id, String userId, long balancePaise){
}