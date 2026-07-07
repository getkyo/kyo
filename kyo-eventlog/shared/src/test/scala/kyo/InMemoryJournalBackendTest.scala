package kyo

/** Runs the backend contract against the in-memory backend. */
class InMemoryJournalBackendTest extends JournalBackendTest(Journal.Backend.inMemory)
