// node:sqlite (intégré à Node ≥ 22.13) est encore marqué expérimental : on masque l'avertissement au chargement.
const emit = process.emitWarning;
process.emitWarning = function (warning, ...rest) {
  if (String(warning?.message ?? warning).includes('SQLite')) return;
  return emit.call(process, warning, ...rest);
};
const { DatabaseSync } = await import('node:sqlite');
process.emitWarning = emit;

export { DatabaseSync };
