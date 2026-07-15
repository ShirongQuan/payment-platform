package org.example.auth.authorisation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.auth.authorisation.domain.Authorisation;
import org.example.auth.authorisation.domain.AuthorisationStatus;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class AuthorisationMapperTest {

  private final AuthorisationMapper mapper = Mappers.getMapper(AuthorisationMapper.class);

  @Test
  void shouldMapDomainToEntity() {
    Authorisation authorisation =
        new Authorisation(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "idem-1",
            new BigDecimal("10.00"),
            "GBP",
            "merchant-1",
            AuthorisationStatus.AUTHORISED,
            "",
            OffsetDateTime.parse("2026-07-15T09:00:00Z"),
            OffsetDateTime.parse("2026-07-15T09:01:00Z"));

    AuthorisationEntity entity = mapper.toEntity(authorisation);

    assertThat(entity.getId()).isEqualTo(authorisation.getId());
    assertThat(entity.getAccountId()).isEqualTo(authorisation.getAccountId());
    assertThat(entity.getIdempotencyKey()).isEqualTo(authorisation.getIdempotencyKey());
    assertThat(entity.getAmount()).isEqualByComparingTo(authorisation.getAmount());
    assertThat(entity.getCurrencyCode()).isEqualTo(authorisation.getCurrencyCode());
    assertThat(entity.getMerchantReference()).isEqualTo(authorisation.getMerchantReference());
    assertThat(entity.getStatus()).isEqualTo(authorisation.getStatus());
    assertThat(entity.getFailureReason()).isEqualTo(authorisation.getFailureReason());
    assertThat(entity.getCreatedAt()).isEqualTo(authorisation.getCreatedAt());
    assertThat(entity.getUpdatedAt()).isEqualTo(authorisation.getUpdatedAt());
  }

  @Test
  void shouldMapEntityToDomain() {
    AuthorisationEntity entity = new AuthorisationEntity();
    entity.setId(UUID.randomUUID());
    entity.setAccountId(UUID.randomUUID());
    entity.setIdempotencyKey("idem-2");
    entity.setAmount(new BigDecimal("15.50"));
    entity.setCurrencyCode("usd");
    entity.setMerchantReference("merchant-2");
    entity.setStatus(AuthorisationStatus.DECLINED);
    entity.setFailureReason("Insufficient funds");
    entity.setCreatedAt(OffsetDateTime.parse("2026-07-15T08:00:00Z"));
    entity.setUpdatedAt(OffsetDateTime.parse("2026-07-15T08:01:00Z"));

    Authorisation authorisation = mapper.toAuthorisation(entity);

    assertThat(authorisation.getId()).isEqualTo(entity.getId());
    assertThat(authorisation.getAccountId()).isEqualTo(entity.getAccountId());
    assertThat(authorisation.getIdempotencyKey()).isEqualTo(entity.getIdempotencyKey());
    assertThat(authorisation.getAmount()).isEqualByComparingTo(entity.getAmount());
    assertThat(authorisation.getCurrencyCode()).isEqualTo("USD");
    assertThat(authorisation.getMerchantReference()).isEqualTo(entity.getMerchantReference());
    assertThat(authorisation.getStatus()).isEqualTo(entity.getStatus());
    assertThat(authorisation.getFailureReason()).isEqualTo(entity.getFailureReason());
    assertThat(authorisation.getCreatedAt()).isEqualTo(entity.getCreatedAt());
    assertThat(authorisation.getUpdatedAt()).isEqualTo(entity.getUpdatedAt());
  }
}

