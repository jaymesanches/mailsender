-- A coluna passa a guardar a conta da ultima tentativa, nao so a do envio bem
-- sucedido: em FAILED e REJECTED ela e o que atribui a falha a uma caixa.
ALTER TABLE emails RENAME COLUMN sent_by_account TO last_account;
