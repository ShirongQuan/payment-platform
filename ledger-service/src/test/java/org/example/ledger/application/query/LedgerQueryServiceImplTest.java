package org.example.ledger.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.example.ledger.api.AccountEventsResponse;
import org.example.ledger.api.AuthenticationResponse;
import org.example.ledger.infrastructure.persistence.AccountEventView;
import org.example.ledger.infrastructure.persistence.LedgerEntryEntity;
import org.example.ledger.infrastructure.persistence.LedgerEntryMapper;
import org.example.ledger.infrastructure.persistence.LedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerQueryServiceImplTest {

  @Mock private LedgerEntryRepository ledgerEntryRepository;
  @Mock private LedgerEntryMapper ledgerEntryMapper;

  @InjectMocks private LedgerQueryServiceImpl ledgerQueryService;

  @Test
  void shouldGetAuthorisationsById() {
    UUID authorisationId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    LedgerEntryEntity entity =
        new LedgerEntryEntity(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "AUTHORISATION",
            authorisationId,
            UUID.randomUUID(),
            authorisationId,
            "AUTHORISATION_AUTHORISED",
            "AUTHORISED",
            new BigDecimal("10.00"),
            "GBP",
            "merchant-1",
            "idem-1",
            OffsetDateTime.parse("2026-07-14T10:00:00Z"),
            OffsetDateTime.parse("2026-07-14T10:00:00Z"),
            java.util.Map.of("status", "AUTHORISED"));

    AuthenticationResponse response =
        new AuthenticationResponse(
            authorisationId,
            entity.getAccountId(),
            "merchant-1",
            new BigDecimal("10.00"),
            "GBP",
            "AUTHORISED",
            entity.getCreatedAt(),
            entity.getEventId());

    when(ledgerEntryRepository.findAuthorisationsById(authorisationId)).thenReturn(List.of(entity));
    when(ledgerEntryMapper.toAuthorisationResponse(entity)).thenReturn(response);

    List<AuthenticationResponse> result = ledgerQueryService.getAuthorisationsById(authorisationId);

    assertThat(result).containsExactly(response);
    verify(ledgerEntryRepository).findAuthorisationsById(authorisationId);
    verify(ledgerEntryMapper).toAuthorisationResponse(entity);
  }

  @Test
  void shouldGetAccountEventsByAccountId() {
    UUID accountId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID aggregateId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    UUID eventId = UUID.fromString("99999999-9999-9999-9999-999999999999");
    OffsetDateTime occurredAt = OffsetDateTime.parse("2026-07-14T10:00:00Z");

    AccountEventView view = mock(AccountEventView.class);
    when(view.getEventId()).thenReturn(eventId);
    when(view.getEventType()).thenReturn("AUTHORISATION_AUTHORISED");
    when(view.getAggregateId()).thenReturn(aggregateId);
    when(view.getOccurredAt()).thenReturn(occurredAt);
    when(view.getAmount()).thenReturn(new BigDecimal("10.00"));
    when(view.getCurrencyCode()).thenReturn("GBP");

    when(ledgerEntryRepository.findAccountEventsById(accountId)).thenReturn(List.of(view));

    AccountEventsResponse result = ledgerQueryService.getAccountEvents(accountId);

    assertThat(result.accountId()).isEqualTo(accountId);
    assertThat(result.events()).hasSize(1);
    assertThat(result.events().getFirst().eventId()).isEqualTo(eventId);
    assertThat(result.events().getFirst().eventType()).isEqualTo("AUTHORISATION_AUTHORISED");
    verify(ledgerEntryRepository).findAccountEventsById(accountId);
  }
}
