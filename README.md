<a href="https://fireflysoftware.io/openplatform/">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fireflysoftwaresolutions/.github/main/profile/assets/banner-dark.svg">
  <img src="https://raw.githubusercontent.com/fireflysoftwaresolutions/.github/main/profile/assets/banner-light.svg" alt="Firefly OpenPlatform - Payments - Experience" width="100%">
</picture>
</a>

# Firefly OpenPlatform - Payments - Experience

Developed by the Firefly Software Foundation. [dev@fireflysoftware.io](mailto:dev@fireflysoftware.io)

[Website](https://fireflysoftware.io/) · [OpenPlatform](https://fireflysoftware.io/openplatform/)

This is the payments journey. A person makes an internal transfer, sends an outbound payment, sees an inbound credit, and reads the status. It is not a copy of the domain hub, and it is not part of accounts.

Each step resolves identity, checks access, and then calls one port: `DomainPaymentsPort`. `SdkDomainPaymentsAdapter` is that port's adapter. It calls `domain-payments-sdk`. A future BPM replaces `SdkDomainPaymentsAdapter`. The experience service does not import the ledger, `library-rails`, `library-psps`, the payments core SDK, or the domain coordinator.

`sample-journey` resolves to `11111111-1111-4111-8111-111111111111` and can create and read a payment. `denied-actor` resolves to `22222222-2222-4222-8222-222222222222` and receives `ACTOR_NOT_ENTITLED` before the domain port is called.

The domain hub decides the posting. In sandbox mode the hub completes an internal posting with no third party, an outbound sync that debits the customer and credits outbound clearing, and an inbound sync that debits inbound clearing and credits the customer. See the domain README for the ledger rules, the idempotency key, and why cancel is not exposed.

`core-banking-payments` has no cancel or recall operation for a payment order. This journey therefore exposes status read and does not offer cancel. A successful cancel is not available to compensate the ledger.

## Operations

| Method | Path | Journey step |
| --- | --- | --- |
| `POST` | `/api/v1/payments/transfers` | Internal transfer or outbound payment. The scheme is on the request. |
| `POST` | `/api/v1/payments/inbound-credits` | Inbound credit accepted by the domain sync port. |
| `GET` | `/api/v1/payments/{paymentId}` | Status. |

Send `X-Firefly-Username`. A blank user name is `sample-journey`.

Identity calls `POST /api/v1/identity/subjects` when the identity adapter is the security-center adapter. Access calls `POST /api/v1/access/checks` for the payment resource. The sandbox adapters in this module answer those checks without a socket so the sample journey can run. Replace `SandboxIdentityAdapter` and `SandboxAccessAdapter` to call domain-core-security-center, and keep `SdkDomainPaymentsAdapter` pointed at the payments domain.

```yaml
server:
  port: 8102

firefly:
  experience:
    payments:
      domain-base-url: http://localhost:8092
```

The domain service defaults to `firefly.payments.mode=sandbox`.

```bash
mvn test
```
