import React from 'react';
import { BaseEdge, EdgeLabelRenderer, EdgeProps, getSmoothStepPath } from '@xyflow/react';

// Кастомное ребро поверх smoothstep: линия пути считается как обычно, но
// подпись не привязана к её середине — она ставится в абсолютных координатах
// канваса (data.labelAbsX/labelAbsY), вычисленных в flowUtils как точка слева
// от узла-источника. Благодаря этому подпись никогда не попадает на узел или
// на линию ребра, а у каждого шага — собственная строка по Y, так что подписи
// разных шагов не накладываются друг на друга.
export const LabeledEdge: React.FC<EdgeProps> = (props) => {
  const {
    sourceX, sourceY, targetX, targetY,
    sourcePosition, targetPosition,
    style, label, labelStyle, labelBgStyle, labelBgPadding,
    markerEnd, markerStart, pathOptions, data,
  } = props;

  const [edgePath, labelX, labelY] = getSmoothStepPath({
    sourceX, sourceY, targetX, targetY,
    sourcePosition, targetPosition,
    borderRadius: pathOptions?.borderRadius,
    offset: pathOptions?.offset,
  });

  const absX = data?.labelAbsX as number | undefined;
  const absY = data?.labelAbsY as number | undefined;
  const posX = absX ?? labelX;
  const posY = absY ?? labelY;
  const padX = labelBgPadding ? labelBgPadding[0] : 5;
  const padY = labelBgPadding ? labelBgPadding[1] : 3;

  return (
    <>
      <BaseEdge path={edgePath} markerEnd={markerEnd} markerStart={markerStart} style={style} />
      {label != null && label !== '' && (
        <EdgeLabelRenderer>
          <div
            style={{
              position: 'absolute',
              transform: `translate(-50%, -50%) translate(${posX}px,${posY}px)`,
              pointerEvents: 'none',
              whiteSpace: 'nowrap',
              fontSize: labelStyle?.fontSize,
              fontWeight: labelStyle?.fontWeight,
              color: labelStyle?.fill as string | undefined,
              background: labelBgStyle?.fill as string | undefined,
              border: labelBgStyle?.stroke
                ? `${labelBgStyle.strokeWidth ?? 1}px solid ${labelBgStyle.stroke}`
                : undefined,
              borderRadius: labelBgStyle?.rx ?? 5,
              padding: `${padY}px ${padX}px`,
              zIndex: (props as { zIndex?: number }).zIndex,
            }}
          >
            {label}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
};
