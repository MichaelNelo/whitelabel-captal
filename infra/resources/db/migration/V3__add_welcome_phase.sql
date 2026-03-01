-- Add welcome phase as the new default initial state
-- Update any existing sessions in identification_question to welcome
-- (This is mainly for development; production shouldn't have existing sessions during initial deploy)
UPDATE sessions SET phase = 'welcome' WHERE phase = 'identification_question';
