import { FlightStage, MessageType } from './sequence';

export interface IncomingMessageRequest {
  messageType: MessageType;
  templateName: string;
  aircraftId: string;
  flightNumber?: string;
  metadataJson?: string;
  /** Идентификатор сообщения от внешней ACARS-системы — ключ идемпотентности шлюза (P2-1). */
  externalMessageId?: string;
}

export interface FlightStageChangeRequest {
  aircraftId: string;
  flightNumber?: string;
  newStage: FlightStage;
}

export interface MessageResponse {
  id: number;
  messageType: MessageType;
  templateName: string;
  aircraftId: string;
  flightNumber: string | null;
  receivedAt: string;
  metadataJson: string | null;
  /** Идентификатор сообщения от внешней ACARS-системы — ключ идемпотентности шлюза (P2-1). */
  externalMessageId?: string;
}
