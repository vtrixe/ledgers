-- Balance of an account in an asset (overdraft check): SUM(amount) served from the index alone.
CREATE INDEX postings_account_asset_idx ON postings (account_id, asset) INCLUDE (amount);
