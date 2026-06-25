package org.example.authservice.authorisation.infrastructure;

import org.example.authservice.authorisation.domain.Authorisation;

public class AuthorisationMapper {
  public static AuthorisationEntity toEntity(Authorisation authorisation) {
    AuthorisationEntity entity = new AuthorisationEntity();
    entity.setId(authorisation.getId());
    entity.setAccountId(authorisation.getAccountId());
    entity.setIdempotencyKey(authorisation.getIdempotencyKey());
    entity.setAmount(authorisation.getAmount());
    entity.setCurrencyCode(authorisation.getCurrencyCode());
    entity.setMerchantReference(authorisation.getMerchantReference());
    entity.setStatus(authorisation.getStatus());
    entity.setFailureReason(authorisation.getFailureReason());
    entity.setCreatedAt(authorisation.getCreatedAt());
    entity.setUpdatedAt(authorisation.getUpdatedAt());
    return entity;
  }

  public static Authorisation toAuthorisation(AuthorisationEntity entity) {
    return new Authorisation(
        entity.getId(),
        entity.getAccountId(),
        entity.getIdempotencyKey(),
        entity.getAmount(),
        entity.getCurrencyCode(),
        entity.getMerchantReference(),
        entity.getStatus(),
        entity.getFailureReason(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
