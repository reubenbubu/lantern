// Long dotted names (org.openwms.tms.transportation) wrap after a dot, colon or slash rather than
// mid-word.
import { Fragment } from 'react';

export function Breakable({ text }: { text: string }) {
  const parts = text.split(/(?<=[.:/$])/);
  return (
    <>
      {parts.map((part, i) => (
        <Fragment key={i}>
          {i > 0 && <wbr />}
          {part}
        </Fragment>
      ))}
    </>
  );
}
