package org.example.auth.authorisation.infrastructure;

import org.example.auth.authorisation.domain.Authorisation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Maps between the {@link Authorisation} domain model and the {@link AuthorisationEntity} JPA
 * entity, keeping the domain layer independent of JPA.
 */
@Mapper(componentModel = "spring")
public interface AuthorisationMapper {

  /** Converts a domain {@link Authorisation} to a new (unmanaged) {@link AuthorisationEntity}. */
  @Mapping(target = "version", ignore = true)
  AuthorisationEntity toEntity(Authorisation authorisation);

  /** Rehydrates an {@link Authorisation} domain object from a managed {@link AuthorisationEntity}. */
  default Authorisation toAuthorisation(AuthorisationEntity entity) {
    if (entity == null) {
      return null;
    }
    return new Authorisation(
        entity.getId(),
        entity.getAccountId(),
        entity.getAmount(),
        entity.getCurrencyCode(),
        entity.getMerchantReference(),
        entity.getStatus(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
