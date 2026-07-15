package org.example.auth.authorisation.infrastructure;

import org.example.auth.authorisation.domain.Authorisation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AuthorisationMapper {

  @Mapping(target = "version", ignore = true)
  AuthorisationEntity toEntity(Authorisation authorisation);

  default Authorisation toAuthorisation(AuthorisationEntity entity) {
    if (entity == null) {
      return null;
    }
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
