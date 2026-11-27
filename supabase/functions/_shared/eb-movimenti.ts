// La lettura di movimenti e saldi di un conto Enable Banking, normalizzati.
//
// Stava dentro enable-banking-transactions; da qui la usa anche sal-sync, che importa da sé
// (job del 28 e pulsante di Spese Personali). Una lettura sola: cambiando come si normalizza un
// movimento, cambia per tutt'e due.

import { ENABLE_BANKING_API_BASE, pick } from './enable-banking.ts';

export const normIban = (s: string | null | undefined): string => (s || '').toUpperCase().replace(/[^A-Z0-9]/g, '');

// La carta con cui è stata fatta la spesa, quando la banca la espone: sta in
// debtor_account_additional_identification. Nessuna carta (bonifico, addebito) → null.
function cardOf(tx: any): { issuer: string | null; identification: string | null } | null {
  const list = pick(tx, 'debtor_account_additional_identification', 'debtorAccountAdditionalIdentification') as any[];
  const card = Array.isArray(list) && list.length ? list[0] : null;
  if (!card) return null;
  const issuer = (pick(card, 'issuer') as string) || null;
  const identification = (pick(card, 'identification') as string) || null;
  return issuer || identification ? { issuer, identification } : null;
}

export type Saldo = { name: string | null; amount: number; currency: string | null; date: string | null };
export type Movimento = {
  amount: number; currency: string | null; creditDebit: string | null;
  bookingDate: string | null; valueDate: string | null; description: string;
  counterpartyName: string; counterpartyIban: string | null; externalId: string | null;
  card: { issuer: string | null; identification: string | null } | null;
  bankTransactionCode: string | null; status: string | null;
};

export async function leggiMovimenti(
  accountId: string, jwt: string, psu: Record<string, string>, dateFrom?: string, dateTo?: string,
): Promise<{ balances: Saldo[]; transactions: Movimento[]; pages: number; truncated: boolean }> {
  // I saldi sono un'altra chiamata e un'altra autorizzazione: se la banca non li concede il
  // resto deve funzionare lo stesso, quindi l'errore qui non ferma i movimenti.
  let balances: Saldo[] = [];
  try {
    const balRes = await fetch(`${ENABLE_BANKING_API_BASE}/accounts/${accountId}/balances`, {
      headers: { Authorization: 'Bearer ' + jwt, ...psu },
    });
    if (balRes.ok) {
      const balData = await balRes.json();
      const list: any[] = Array.isArray(balData.balances) ? balData.balances : [];
      balances = list.map((b: any) => {
        const amountRaw = pick(b, 'balance_amount', 'balanceAmount') as any;
        return {
          name: (pick(b, 'name', 'balance_type', 'balanceType') as string) || null,
          amount: parseFloat((pick(amountRaw, 'amount') as string) || '0') || 0,
          currency: (pick(amountRaw, 'currency') as string) || null,
          date: (pick(b, 'reference_date', 'referenceDate', 'last_change_date_time') as string) || null,
        };
      });
    }
  } catch {
    // saldi non disponibili: si va avanti con i soli movimenti
  }

  const MAX_PAGES = 50;
  const raw: any[] = [];
  let continuationKey: string | null = null;
  let pages = 0;
  do {
    const url = new URL(`${ENABLE_BANKING_API_BASE}/accounts/${accountId}/transactions`);
    if (dateFrom) url.searchParams.set('date_from', dateFrom);
    if (dateTo) url.searchParams.set('date_to', dateTo);
    if (continuationKey) url.searchParams.set('continuation_key', continuationKey);
    const txRes = await fetch(url.toString(), { headers: { Authorization: 'Bearer ' + jwt, ...psu } });
    const txData = await txRes.json();
    if (!txRes.ok) throw new Error('Errore Enable Banking: ' + (txData?.message || txRes.status));
    const page: any[] = Array.isArray(txData.transactions) ? txData.transactions : [];
    raw.push(...page);
    continuationKey = txData.continuation_key || null;
    pages++;
  } while (continuationKey && pages < MAX_PAGES);

  const transactions = raw.map((tx: any): Movimento => {
    const amountRaw = pick(tx, 'transaction_amount', 'transactionAmount') as any;
    const absAmount = Math.abs(parseFloat((pick(amountRaw, 'amount') as string) || '0') || 0);
    const indicator = ((pick(tx, 'credit_debit_indicator', 'creditDebitIndicator') as string) || '').toUpperCase();
    const isCredit = indicator === 'CRDT';
    // Chi sta dall'altro capo: il denaro che entra arriva da qualcuno (debtor), quello che esce
    // va a qualcuno (creditor).
    const party = (isCredit ? pick(tx, 'debtor') : pick(tx, 'creditor')) as any;
    const partyAccount = (isCredit
      ? pick(tx, 'debtor_account', 'debtorAccount')
      : pick(tx, 'creditor_account', 'creditorAccount')) as any;
    const remittanceInfo = pick(
      tx, 'remittance_information', 'remittanceInformation',
      'remittance_information_unstructured', 'remittanceInformationUnstructured',
    );
    const remittanceText = Array.isArray(remittanceInfo) ? remittanceInfo.join(' ') : ((remittanceInfo as string) || '');
    const code = pick(tx, 'bank_transaction_code', 'bankTransactionCode') as any;
    return {
      // Importo con segno: entrate positive, uscite negative.
      amount: isCredit ? absAmount : -absAmount,
      currency: (pick(amountRaw, 'currency') as string) || null,
      creditDebit: indicator || null,
      bookingDate: (pick(tx, 'booking_date', 'bookingDate') as string) || null,
      valueDate: (pick(tx, 'value_date', 'valueDate') as string) || null,
      description: (remittanceText || '').trim(),
      counterpartyName: (((party && (pick(party, 'name') as string)) || '') as string).trim(),
      counterpartyIban: normIban(
        (partyAccount &&
          ((pick(partyAccount, 'iban') as string) ||
            (pick(pick(partyAccount, 'identification') as any, 'identification', 'iban') as string) ||
            (pick(partyAccount, 'identification') as string))) as string,
      ) || null,
      externalId: (pick(tx, 'entry_reference', 'entryReference', 'transaction_id', 'transactionId') as string) || null,
      card: cardOf(tx),
      // Codice ISO 20022 (domain/family/sub_family): l'unica classificazione che passa dall'API.
      bankTransactionCode: code
        ? [pick(code, 'domain', 'domain_code'), pick(code, 'family', 'family_code'), pick(code, 'sub_family', 'subFamily', 'sub_family_code')]
            .filter(Boolean).join('/')
        : null,
      status: (pick(tx, 'status', 'transaction_status') as string) || null,
    };
  });

  return { balances, transactions, pages, truncated: pages >= MAX_PAGES };
}
